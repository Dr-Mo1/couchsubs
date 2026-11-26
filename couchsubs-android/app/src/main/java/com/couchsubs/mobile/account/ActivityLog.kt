// SPDX-License-Identifier: AGPL-3.0-only
// 1.0.48 part B: privacy-respecting activity log sent in batches to /functions/v1/events.
// Never records user subtitles, Sony account data, IP addresses or location. Events are queued in memory,
// mirrored to a small private file so they survive restarts, and sent only after the one-time privacy notice is accepted.
package com.couchsubs.mobile.account

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import com.couchsubs.mobile.BuildConfig
import com.couchsubs.mobile.core.Log
import com.couchsubs.mobile.remoteplay.DisconnectKind
import com.couchsubs.mobile.translation.Http
import com.couchsubs.mobile.translation.TargamgiGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One activity event queued for POST /functions/v1/events. */
data class ActivityEvent(
    val type: String,
    val at: String,
    val data: Map<String, Any?> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val obj = JSONObject().put("type", type).put("at", at)
        if (data.isNotEmpty()) obj.put("data", mapToJson(data))
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): ActivityEvent? {
            val type = obj.optString("type", "").trim()
            if (type !in ActivityRules.ALLOWED_TYPES) return null
            val at = obj.optString("at", "").trim().ifEmpty { Instant.now().toString() }
            val dataObj = obj.optJSONObject("data")
            val data = if (dataObj == null) emptyMap() else jsonToMap(dataObj)
            return ActivityEvent(type, at, data)
        }

        private fun mapToJson(map: Map<String, Any?>): JSONObject {
            val obj = JSONObject()
            for ((k, v) in map) {
                when (v) {
                    null -> Unit
                    is Boolean, is Int, is Long, is Double, is String -> obj.put(k, v)
                    else -> obj.put(k, v.toString())
                }
            }
            return obj
        }

        private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (!obj.isNull(key)) map[key] = obj.opt(key)
            }
            return map
        }
    }
}

/** Static device metadata sent alongside each events batch (no personal data, no IP). */
data class ActivityDeviceInfo(
    val deviceId: String,
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
)

/** Pure rules for activity event validation, sanitization and limits (JVM-tested). */
object ActivityRules {
    const val MAX_EVENTS_PER_BATCH = 100
    const val MAX_STORED_EVENTS = 500
    const val MAX_DATA_BYTES = 2 * 1024
    const val MAX_ERROR_MESSAGE_CHARS = 300
    const val FLUSH_INTERVAL_MS = 60_000L
    const val HEARTBEAT_INTERVAL_MS = 60_000L
    /** Server enforces at most one batch per 5 s per user; leave a small buffer to avoid unnecessary 429s. */
    const val MIN_SEND_GAP_MS = 5_200L

    val ALLOWED_TYPES = setOf(
        "app_open", "app_close", "screen", "translate_start", "translate_stop",
        "ps5_connect", "ps5_disconnect", "ps5_error", "setting_change", "error",
        "update_check", "heartbeat", "subscribe_screen",
    )

    /** Strict allowlist of non-personal settings that may be recorded as `setting_change`. */
    val ALLOWED_SETTING_KEYS = setOf(
        "ui_language", "translationStyle", "engine", "target", "playerCharacter",
        "packsEnabled", "showSpeakerNames", "speakerColors", "autoScreenText",
        "keepScreenOn", "backgroundPip", "fastSubtitleDetection", "stackPrevious",
        "subtitleScale", "textColor", "tvPlacement", "tvDisplayMode",
        "tvBackground", "tvBackgroundShape", "platform", "areaPreset",
        "watchSubtitleBackground", "autoReadGamePhone", "updateChannel",
    )

    private val HEX_CODE = Regex("^0x[0-9a-fA-F]+$")

    /**
     * Produces a safe PS5 error code with NO personal data and NO IP address (e.g. NOT_FOUND's detail holds the host IP
     * and is stripped). Only enum kind and hex rejection codes like 0x80108b12 are kept.
     */
    fun safePs5ErrorCode(kind: DisconnectKind, detail: String?): String {
        val base = kind.name.lowercase()
        val trimmed = detail?.trim()
        return if (kind == DisconnectKind.REJECTED && trimmed != null && HEX_CODE.matches(trimmed)) {
            "${base}_${trimmed.lowercase()}"
        } else {
            base
        }
    }

    /** Caps an error message to [MAX_ERROR_MESSAGE_CHARS] characters and removes line breaks. */
    fun sanitizeErrorMessage(message: String?): String =
        message.orEmpty().replace('\r', ' ').replace('\n', ' ').trim().take(MAX_ERROR_MESSAGE_CHARS)

    /** Ensures the serialized `data` JSON object stays within the server's 2 KB limit. */
    fun capData(data: Map<String, Any?>): Map<String, Any?> {
        if (data.isEmpty()) return emptyMap()
        if (serializedSize(data) <= MAX_DATA_BYTES) return data
        // Trim string values progressively; if still too big, keep only small scalar fields.
        val trimmed = LinkedHashMap<String, Any?>()
        for ((k, v) in data) {
            trimmed[k] = if (v is String) v.take(256) else v
        }
        if (serializedSize(trimmed) <= MAX_DATA_BYTES) return trimmed
        val minimal = LinkedHashMap<String, Any?>()
        for ((k, v) in trimmed) {
            val candidate = LinkedHashMap(minimal).apply { put(k, v) }
            if (serializedSize(candidate) <= MAX_DATA_BYTES) minimal[k] = v
        }
        return minimal
    }

    private fun serializedSize(data: Map<String, Any?>): Int {
        val obj = JSONObject()
        for ((k, v) in data) {
            if (v != null) obj.put(k, v)
        }
        val text = obj.toString()
        return maxOf(text.length, text.toByteArray(Charsets.UTF_8).size)
    }
}

/**
 * In-memory event queue mirrored atomically to a small private file so events survive restarts.
 * Plain Kotlin so batching, the 100-event cap and persistence are JVM-testable.
 */
class ActivityQueue(
    private val file: File?,
    private val maxStored: Int = ActivityRules.MAX_STORED_EVENTS,
    private val maxBatch: Int = ActivityRules.MAX_EVENTS_PER_BATCH,
) {
    private val lock = Any()
    private val items = ArrayDeque<ActivityEvent>()

    init {
        loadFromDisk()
    }

    val size: Int get() = synchronized(lock) { items.size }

    fun enqueue(event: ActivityEvent): Boolean = synchronized(lock) {
        if (event.type !in ActivityRules.ALLOWED_TYPES) return false
        val safe = event.copy(data = ActivityRules.capData(event.data))
        items.addLast(safe)
        while (items.size > maxStored) items.removeFirst()
        saveToDiskLocked()
        true
    }

    /** Returns up to [maxBatch] (100) oldest events without removing them yet. */
    fun peekBatch(limit: Int = maxBatch): List<ActivityEvent> = synchronized(lock) {
        val count = minOf(limit.coerceAtMost(maxBatch), items.size)
        if (count <= 0) emptyList() else items.take(count)
    }

    /** Removes the oldest [count] events after a successful 200 reply and mirrors to disk. */
    fun acknowledge(count: Int) = synchronized(lock) {
        val drop = minOf(count, items.size)
        repeat(drop) { items.removeFirst() }
        saveToDiskLocked()
    }

    private fun loadFromDisk() {
        val source = file ?: return
        if (!source.isFile) return
        try {
            val array = JSONArray(source.readText(Charsets.UTF_8))
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val parsed = ActivityEvent.fromJson(obj) ?: continue
                items.addLast(parsed)
            }
            while (items.size > maxStored) items.removeFirst()
        } catch (_: IOException) {
        } catch (_: JSONException) {
        }
    }

    private fun saveToDiskLocked() {
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            val array = JSONArray()
            for (event in items) array.put(event.toJson())
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(array.toString(), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
        } catch (_: IOException) {
        }
    }
}

sealed interface FlushResult {
    data class Sent(val count: Int, val remaining: Int) : FlushResult
    data object Empty : FlushResult
    data object ConsentPending : FlushResult
    data object RateLimited : FlushResult
    data class RetryLater(val http: Int? = null) : FlushResult
}

/**
 * Sends queued batches to POST /functions/v1/events. Handles 401 refresh+retry once, 403 `account_not_found`
 * (calls [ensureAccount] first, then retries once), and 429 `too_frequent` (keeps events and respects the 5 s gap).
 */
class ActivityClient(
    val queue: ActivityQueue,
    private val http: Http,
    private val deviceInfo: ActivityDeviceInfo,
    private val token: () -> String?,
    private val refresh: () -> String?,
    private val ensureAccount: () -> Boolean,
    private val isConsented: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val baseUrl: String = TargamgiGate.SUPABASE_URL,
    private val apiKey: String = TargamgiGate.PUBLISHABLE_KEY,
) {
    companion object {
        const val TIMEOUT_MS = 15_000
    }

    private val sendLock = Any()
    @Volatile private var nextAllowedSendAt = 0L

    /** Sends at most one batch (up to 100 events). Never drops events on 429, 403, 5xx or offline. */
    fun flushOnce(): FlushResult = synchronized(sendLock) {
        if (!isConsented()) return FlushResult.ConsentPending
        val batch = queue.peekBatch(ActivityRules.MAX_EVENTS_PER_BATCH)
        if (batch.isEmpty()) return FlushResult.Empty
        val now = clock()
        if (now < nextAllowedSendAt) return FlushResult.RateLimited

        val body = buildPayload(batch)
        val reply = sendAuthed(body) ?: return FlushResult.RetryLater(null)
        when (reply.first) {
            in 200..299 -> {
                nextAllowedSendAt = clock() + ActivityRules.MIN_SEND_GAP_MS
                queue.acknowledge(batch.size)
                FlushResult.Sent(batch.size, queue.size)
            }
            429 -> {
                // Server enforces 1 batch per 5 s: keep events and retry later; never drop.
                nextAllowedSendAt = clock() + ActivityRules.MIN_SEND_GAP_MS
                FlushResult.RateLimited
            }
            400 -> {
                // Malformed payload rejected permanently by server: drop this batch so it cannot wedge future events.
                queue.acknowledge(batch.size)
                FlushResult.RetryLater(400)
            }
            else -> FlushResult.RetryLater(reply.first)
        }
    }

    private fun buildPayload(batch: List<ActivityEvent>): String {
        val events = JSONArray()
        for (ev in batch) events.put(ev.toJson())
        return JSONObject()
            .put("device_id", deviceInfo.deviceId.trim().take(128))
            .put("app_version", deviceInfo.appVersion.take(64))
            .put("device_model", deviceInfo.deviceModel.take(128))
            .put("android_version", deviceInfo.androidVersion.take(64))
            .put("events", events)
            .toString()
    }

    private fun sendAuthed(body: String): Pair<Int, String>? {
        val currentToken = token() ?: return null
        var reply = post(body, currentToken) ?: return null
        if (reply.first == 401) {
            val fresh = refresh() ?: return reply
            reply = post(body, fresh) ?: return reply
        }
        if (reply.first == 403 && isAccountNotFound(reply.second)) {
            if (ensureAccount()) {
                val fresh = token() ?: return reply
                reply = post(body, fresh) ?: return reply
            }
        }
        return reply
    }

    private fun isAccountNotFound(body: String): Boolean = try {
        JSONObject(body).optString("error") == "account_not_found"
    } catch (_: JSONException) {
        false
    }

    private fun post(body: String, bearer: String): Pair<Int, String>? = try {
        http.send(
            "POST",
            baseUrl.trimEnd('/') + "/functions/v1/events",
            mapOf(
                "apikey" to apiKey,
                "Authorization" to "Bearer $bearer",
                "Content-Type" to "application/json",
            ),
            body,
            TIMEOUT_MS,
        )
    } catch (_: IOException) {
        null
    }
}

/** Process-wide activity logger (initialized in Services.init). */
object ActivityLog {
    private const val TAG = "SubTvActivity"
    private const val PREF_PRIVACY_ACCEPTED = "privacy_notice_accepted"
    private const val QUEUE_FILE = "activity-events.json"

    private lateinit var prefs: SharedPreferences
    private lateinit var client: ActivityClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "SubTv activity").apply { isDaemon = true } }
    private val startedActivities = AtomicInteger(0)
    @Volatile private var foregroundSinceMs = 0L
    @Volatile private var isForeground = false
    @Volatile private var settingListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val privacyAcceptedFlow = MutableStateFlow(true)
    /** False on first launch until the user taps OK on the one-time privacy notice dialog. */
    val privacyAccepted: StateFlow<Boolean> = privacyAcceptedFlow

    fun init(app: Application) {
        if (::client.isInitialized) return
        prefs = app.getSharedPreferences("subtv_account", Context.MODE_PRIVATE)
        privacyAcceptedFlow.value = prefs.getBoolean(PREF_PRIVACY_ACCEPTED, false)
        val info = ActivityDeviceInfo(
            deviceId = SubTvAccount.deviceId(),
            appVersion = BuildConfig.VERSION_NAME,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        val queue = ActivityQueue(File(app.filesDir, QUEUE_FILE))
        client = ActivityClient(
            queue = queue,
            http = Http.Default,
            deviceInfo = info,
            token = SubTvAccount::accessTokenBlocking,
            refresh = SubTvAccount::refreshTokenBlocking,
            ensureAccount = { runBlocking { SubTvAccount.refreshStatus() } },
            isConsented = { privacyAcceptedFlow.value },
        )
        attachLifecycle(app)
        attachSettingsObserver(app)
        // Periodic 60 s batch flush while the app is open or translation is running.
        scope.launch {
            while (true) {
                delay(ActivityRules.FLUSH_INTERVAL_MS)
                if (isForeground || com.couchsubs.mobile.service.LiveState.status.value.running) {
                    runFlush()
                }
            }
        }
    }

    /** Called when the user taps OK on the one-time privacy notice dialog. */
    fun acceptPrivacyNotice() {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(PREF_PRIVACY_ACCEPTED, true).commit()
        privacyAcceptedFlow.value = true
        flushAsync()
    }

    private fun attachLifecycle(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities.getAndIncrement() == 0) {
                    isForeground = true
                    foregroundSinceMs = System.currentTimeMillis()
                    record("app_open")
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity.isChangingConfigurations) {
                    startedActivities.decrementAndGet()
                    return
                }
                if (startedActivities.decrementAndGet() <= 0) {
                    startedActivities.set(0)
                    isForeground = false
                    val seconds = if (foregroundSinceMs > 0L) ((System.currentTimeMillis() - foregroundSinceMs) / 1000L).coerceAtLeast(0L) else 0L
                    foregroundSinceMs = 0L
                    record("app_close", mapOf("seconds" to seconds))
                    flushAsync()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun attachSettingsObserver(app: Application) {
        val settingsPrefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == null || key !in ActivityRules.ALLOWED_SETTING_KEYS) return@OnSharedPreferenceChangeListener
            val value = shared.all[key]?.toString() ?: return@OnSharedPreferenceChangeListener
            settingChange(key, value)
        }
        settingListener = listener
        settingsPrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun record(type: String, data: Map<String, Any?> = emptyMap(), synchronous: Boolean = false) {
        if (!::client.isInitialized) return
        val event = ActivityEvent(type, Instant.now().toString(), data)
        if (synchronous) {
            runCatching { client.queue.enqueue(event) }
        } else {
            runCatching { writer.execute { runCatching { client.queue.enqueue(event) } } }
        }
    }

    fun screen(name: String) {
        if (name.isBlank()) return
        record("screen", mapOf("screen" to name.take(64)))
    }

    fun translateStart(style: String, game: String?) {
        val data = LinkedHashMap<String, Any?>()
        data["style"] = style
        if (!game.isNullOrBlank()) data["game"] = game.take(128)
        record("translate_start", data)
    }

    fun translateStop(seconds: Long, lines: Int, style: String, game: String?) {
        val data = LinkedHashMap<String, Any?>()
        data["seconds"] = seconds.coerceAtLeast(0L)
        data["lines"] = lines.coerceAtLeast(0)
        data["style"] = style
        if (!game.isNullOrBlank()) data["game"] = game.take(128)
        record("translate_stop", data)
    }

    fun heartbeat(style: String, game: String?) {
        val data = LinkedHashMap<String, Any?>()
        data["style"] = style
        if (!game.isNullOrBlank()) data["game"] = game.take(128)
        record("heartbeat", data)
    }

    fun ps5Connect() = record("ps5_connect")

    fun ps5Disconnect(code: String? = null) {
        val data = if (code.isNullOrBlank()) emptyMap() else mapOf("code" to code.take(64))
        record("ps5_disconnect", data)
    }

    fun ps5Error(code: String) = record("ps5_error", mapOf("code" to code.take(64)))

    fun settingChange(key: String, value: String) {
        if (key !in ActivityRules.ALLOWED_SETTING_KEYS) return
        record("setting_change", mapOf("key" to key, "value" to value.take(128)))
    }

    fun error(throwable: Throwable, synchronous: Boolean = false) {
        val cls = throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name }.take(128)
        val msg = ActivityRules.sanitizeErrorMessage(throwable.message)
        record("error", mapOf("exception" to cls, "message" to msg), synchronous = synchronous)
    }

    fun error(exceptionClass: String, message: String?) {
        record(
            "error",
            mapOf(
                "exception" to exceptionClass.take(128),
                "message" to ActivityRules.sanitizeErrorMessage(message),
            ),
        )
    }

    fun updateCheck(manual: Boolean, result: String) =
        record("update_check", mapOf("manual" to manual, "result" to result))

    fun subscribeScreen() = record("subscribe_screen")

    /** Triggers an immediate background flush (e.g. on app close/background or translation stop). */
    fun flushAsync() {
        if (!::client.isInitialized || !privacyAcceptedFlow.value) return
        // Submit via writer first so any just-enqueued event is on the queue before flush runs.
        runCatching {
            writer.execute {
                scope.launch { runFlush() }
            }
        }
    }

    private fun runFlush() {
        if (!::client.isInitialized) return
        when (val result = runCatching { client.flushOnce() }.getOrNull()) {
            is FlushResult.Sent -> Log.i(TAG, "sent ${result.count} events (${result.remaining} remaining)")
            is FlushResult.RetryLater -> Log.w(TAG, "events flush deferred (${result.http ?: "offline"})")
            else -> Unit
        }
    }
}
