// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.account

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.couchsubs.mobile.core.Log
import com.couchsubs.mobile.settings.SecretStore
import com.couchsubs.mobile.translation.GateError
import com.couchsubs.mobile.translation.GateException
import com.couchsubs.mobile.translation.Http
import com.couchsubs.mobile.translation.TargamgiGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID

// 1.0.48 "SubTV": an anonymous account (no sign-up screen) with a free trial. Every translation through our server is
// made by this account (see TargamgiGate and AiTranslator's gate mode). This file holds the models, the pure rules
// (JVM-tested), the HTTP client over the app's own Http helper, and the process-wide holder the screens read.

/** A paid plan the owner sells by hand (from account-status; nothing is hard-coded here). */
data class Plan(val id: String, val name: String, val priceEgp: Double, val durationDays: Int)

/** The reply of account-status. [plan] is the server's word (trial, permanent, blocked, paid, code, ...). */
data class AccountStatus(
    val plan: String,
    val planUntil: String?,
    val trialStartedAt: String?,
    val accessActive: Boolean,
    val deviceTrialUsedElsewhere: Boolean,
    val trialDays: Int,
    val maintenanceOn: Boolean,
    val maintenanceMessage: String,
    val announcement: String,
    val minAppVersion: String,
    val paidPlans: List<Plan>,
    val referralCount: Int = 0,
) {
    companion object {
        /** Null when the reply is not the expected JSON. */
        fun parse(json: String): AccountStatus? = try {
            val root = JSONObject(json)
            val account = root.getJSONObject("account")
            val config = root.optJSONObject("config") ?: JSONObject()
            val plans = root.optJSONArray("paid_plans")
            AccountStatus(
                plan = account.text("plan").orEmpty(),
                planUntil = account.text("plan_until"),
                trialStartedAt = account.text("trial_started_at"),
                accessActive = root.optBoolean("access_active", false),
                deviceTrialUsedElsewhere = root.optBoolean("device_trial_used_elsewhere", false),
                trialDays = config.optInt("trial_days", 3),
                maintenanceOn = config.optBoolean("maintenance_on", false),
                maintenanceMessage = config.text("maintenance_message").orEmpty(),
                announcement = config.text("announcement").orEmpty(),
                minAppVersion = config.text("min_app_version").orEmpty(),
                paidPlans = (0 until (plans?.length() ?: 0)).mapNotNull { plans?.optJSONObject(it) }.map {
                    Plan(it.text("id").orEmpty(), it.text("name").orEmpty(), it.optDouble("price_egp", 0.0), it.optInt("duration_days", 0))
                },
                referralCount = account.optInt("referral_count", 0),
            )
        } catch (_: JSONException) {
            null
        }
    }
}

/** A JSON string, or null when the key is missing, JSON null or blank (Android's org.json turns null into "null"). */
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().ifEmpty { null }

/** What the status card says. */
enum class AccessKind { TRIAL_NOT_STARTED, TRIAL, SUBSCRIBED, PERMANENT, ENDED }

/** Which full screen covers the app (see [AccountRules.screen]). */
enum class AccountScreen { NONE, UPDATE_REQUIRED, SUBSCRIBE }

/** Why a code was not accepted, as the UI words it. */
enum class RedeemProblem { INVALID_CODE, CODE_EXPIRED, CODE_EXHAUSTED, ALREADY_REDEEMED, ALREADY_PERMANENT, CODE_INACTIVE, RATE_LIMITED, NETWORK, OTHER }

object AccountRules {
    /** The access token is renewed when fewer than this many ms are left. */
    const val REFRESH_MARGIN_MS = 120_000L
    private const val DAY_MS = 86_400_000L

    fun needsRefresh(expiresAtMs: Long, nowMs: Long): Boolean = expiresAtMs - nowMs < REFRESH_MARGIN_MS

    /** Offline snapshots of non-permanent plans require at least one live server check per 24 hours. */
    const val OFFLINE_GRACE_MS = 24 * 3_600_000L

    fun effectiveNowMs(systemNowMs: Long, storedMaxMs: Long): Long = maxOf(systemNowMs, storedMaxMs)

    private fun isPlanClockExpired(status: AccountStatus, nowMs: Long): Boolean {
        if (status.plan == "permanent") return false
        val endMs = epochMs(status.planUntil) ?: return false
        return endMs <= nowMs
    }

    /**
     * The trial starts at the first translation, so a fresh account has `access_active == false` before then. A "trial" plan
     * that never started, on a device whose trial was not used elsewhere, is allowed (the first translation starts it).
     */
    @JvmOverloads
    fun kind(status: AccountStatus, nowMs: Long = System.currentTimeMillis()): AccessKind = when {
        status.plan == "trial" && status.trialStartedAt == null && !status.deviceTrialUsedElsewhere -> AccessKind.TRIAL_NOT_STARTED
        !status.accessActive -> AccessKind.ENDED
        status.plan == "permanent" -> AccessKind.PERMANENT
        isPlanClockExpired(status, nowMs) -> AccessKind.ENDED
        status.plan == "trial" -> AccessKind.TRIAL
        else -> AccessKind.SUBSCRIBED
    }

    /** Our server will refuse translations: the status says so, or the server just refused one ([gate]). */
    @JvmOverloads
    fun blocksTranslation(status: AccountStatus?, gate: GateError?, nowMs: Long = System.currentTimeMillis()): Boolean =
        gate == GateError.ACCESS_ENDED || (status != null && kind(status, nowMs) == AccessKind.ENDED)

    /** Strict check when starting translation: blocks firewall/clock-backdate bypasses on cached local packs. */
    fun blocksTranslationStrict(status: AccountStatus?, gate: GateError?, nowMs: Long, lastVerifiedAtMs: Long): Boolean {
        if (blocksTranslation(status, gate, nowMs)) return true
        val current = status ?: return true
        if (kind(current, nowMs) == AccessKind.PERMANENT) return false
        if (lastVerifiedAtMs <= 0L) return true
        return nowMs - lastVerifiedAtMs > OFFLINE_GRACE_MS
    }

    /**
     * The full screen to show. An old app version blocks everything. When access has ended (1.0.48 part B: no
     * subscription = no translation at all), the subscribe screen covers the app regardless of the selected engine.
     */
    @JvmOverloads
    fun screen(
        status: AccountStatus?,
        gate: GateError?,
        appVersion: String,
        serverEngine: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
    ): AccountScreen = when {
        status != null && updateRequired(status.minAppVersion, appVersion) -> AccountScreen.UPDATE_REQUIRED
        blocksTranslation(status, gate, nowMs) -> AccountScreen.SUBSCRIBE
        else -> AccountScreen.NONE
    }

    /** True when translation (including local packs and corrections) is allowed to run. */
    @JvmOverloads
    fun canTranslate(status: AccountStatus?, gate: GateError?, nowMs: Long = System.currentTimeMillis()): Boolean =
        !blocksTranslation(status, gate, nowMs)

    /** Whole days of the trial left (rounded up, never below 0): from `plan_until`, else the start plus the trial length. */
    fun trialDaysLeft(status: AccountStatus, nowMs: Long): Int {
        val end = epochMs(status.planUntil) ?: epochMs(status.trialStartedAt)?.plus(status.trialDays * DAY_MS) ?: return status.trialDays
        return ((end - nowMs + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(0)
    }

    /** Exact countdown components remaining until `plan_until` (or trial start + trialDays), or null if permanent/unstarted/expired. */
    data class RemainingTime(val days: Int, val hours: Int, val minutes: Int, val seconds: Int, val totalMs: Long)

    fun remainingTime(status: AccountStatus, nowMs: Long): RemainingTime? {
        val k = kind(status, nowMs)
        if (k != AccessKind.TRIAL && k != AccessKind.SUBSCRIBED) return null
        val end = (epochMs(status.planUntil)
            ?: if (k == AccessKind.TRIAL) epochMs(status.trialStartedAt)?.plus(status.trialDays * DAY_MS) else null)
            ?: return null
        val diff = (end - nowMs).coerceAtLeast(0L)
        val totalSec = diff / 1000L
        val days = (totalSec / 86_400L).toInt()
        val hours = ((totalSec % 86_400L) / 3600L).toInt()
        val minutes = ((totalSec % 3600L) / 60L).toInt()
        val seconds = (totalSec % 60L).toInt()
        return RemainingTime(days, hours, minutes, seconds, diff)
    }

    fun epochMs(iso: String?): Long? = try {
        iso?.trim()?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    } catch (_: DateTimeParseException) {
        null
    }

    /** "Your account number": the first 8 hex characters of the user id, upper case; the owner finds the user by it. */
    fun accountNumber(userId: String?): String? = userId?.filter { it != '-' }?.take(8)?.uppercase()?.ifEmpty { null }

    /** True when [current] is an older version than [minimum] (dotted numbers; "0.1.0-beta.37" reads as 0.1.0.37). */
    fun isOlder(current: String, minimum: String): Boolean {
        val a = versionParts(current)
        val b = versionParts(minimum)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x < y
        }
        return false
    }

    fun updateRequired(minAppVersion: String, current: String): Boolean = minAppVersion.isNotBlank() && isOlder(current, minAppVersion)

    private fun versionParts(version: String) =
        version.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** The ANDROID_ID, else the id saved earlier, else a new random one; the pair says whether to save it. */
    fun deviceId(androidId: String?, saved: String?, generate: () -> String): Pair<String, Boolean> {
        androidId?.trim()?.takeIf { it.length >= 4 }?.let { return it to false }
        saved?.takeIf { it.length >= 4 }?.let { return it to false }
        return generate() to true
    }

    /** Price as the plan list shows it: "100", or "99.5" when it has a fraction. */
    fun priceText(price: Double): String = if (price % 1.0 == 0.0) price.toLong().toString() else price.toString()

    /** redeem-code's `error` value (or "network") to what the screen says. */
    fun redeemProblem(code: String): RedeemProblem = when (code) {
        "invalid_code" -> RedeemProblem.INVALID_CODE
        "code_expired" -> RedeemProblem.CODE_EXPIRED
        "code_exhausted" -> RedeemProblem.CODE_EXHAUSTED
        "already_redeemed" -> RedeemProblem.ALREADY_REDEEMED
        "already_permanent" -> RedeemProblem.ALREADY_PERMANENT
        "code_inactive" -> RedeemProblem.CODE_INACTIVE
        "rate_limited" -> RedeemProblem.RATE_LIMITED
        "network" -> RedeemProblem.NETWORK
        else -> RedeemProblem.OTHER
    }
}

/** The signed-in anonymous user: tokens, when the access token ends (epoch ms) and the user id. */
data class Session(val accessToken: String, val refreshToken: String, val expiresAtMs: Long, val userId: String)

interface SessionStore {
    fun load(): Session?
    fun save(session: Session?)
}

/** Session in the app's private SharedPreferences. */
class PrefsSessionStore(private val prefs: SharedPreferences) : SessionStore {
    override fun load(): Session? {
        val access = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", null) ?: return null
        return Session(access, refresh, prefs.getLong("expires_at", 0L), prefs.getString("user_id", "").orEmpty())
    }

    // commit(), not apply(): a refresh token is single-use, so the new one must reach the disk before the app can die.
    override fun save(session: Session?) {
        val edit = prefs.edit()
        if (session == null) edit.remove("access_token").remove("refresh_token").remove("expires_at").remove("user_id")
        else edit.putString("access_token", session.accessToken).putString("refresh_token", session.refreshToken)
            .putLong("expires_at", session.expiresAtMs).putString("user_id", session.userId)
        edit.commit()
    }
}

sealed interface StatusReply {
    data class Ok(val status: AccountStatus, val rawJson: String = "") : StatusReply
    /** [http] is the status code, null when the server was not reached. */
    data class Failed(val http: Int?) : StatusReply
}

sealed interface RedeemReply {
    data class Success(val plan: String?, val planUntil: String?, val kind: String? = null) : RedeemReply
    /** [code] is the server's `error`, "network" when it was not reached, or "http_<status>". */
    data class Failure(val code: String) : RedeemReply
}

/**
 * The blocking client: anonymous sign-in, token refresh, account-status and redeem-code over the app's own [Http] helper (no
 * new library). Everything here is plain Kotlin so the rules are tested on the JVM with a fake [Http].
 */
class SubTvAccountClient(
    private val http: Http,
    private val store: SessionStore,
    val deviceId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val baseUrl: String = TargamgiGate.SUPABASE_URL,
    private val apiKey: String = TargamgiGate.PUBLISHABLE_KEY,
) {
    companion object {
        const val TIMEOUT_MS = 15_000
    }

    fun userId(): String? = store.load()?.userId?.takeIf { it.isNotEmpty() }

    /** A token with at least [AccountRules.REFRESH_MARGIN_MS] left: signs in when there is no session, refreshes when needed. */
    @Synchronized
    fun accessToken(): String? {
        val session = store.load() ?: return signIn()?.accessToken
        if (!AccountRules.needsRefresh(session.expiresAtMs, clock())) return session.accessToken
        // A failed refresh (no connection) still leaves the old token usable until it really ends.
        return refresh(session)?.accessToken ?: session.accessToken.takeIf { session.expiresAtMs > clock() }
    }

    /** After a 401: refreshes now whatever the clock says; the new token, or null. */
    @Synchronized
    fun forceRefresh(): String? {
        val session = store.load() ?: return signIn()?.accessToken
        return refresh(session)?.accessToken
    }

    fun fetchStatus(): StatusReply {
        val reply = authed("/functions/v1/account-status", JSONObject().put("device_id", deviceId).toString())
            ?: return StatusReply.Failed(null)
        if (reply.first !in 200..299) return StatusReply.Failed(reply.first)
        return AccountStatus.parse(reply.second)?.let { StatusReply.Ok(it, reply.second) } ?: StatusReply.Failed(reply.first)
    }

    fun redeem(code: String): RedeemReply {
        val body = JSONObject().put("code", code.trim()).put("device_id", deviceId).toString()
        val reply = authed("/functions/v1/redeem-code", body) ?: return RedeemReply.Failure("network")
        val json = try {
            JSONObject(reply.second)
        } catch (_: JSONException) {
            null
        }
        if (json != null && reply.first in 200..299 && json.optBoolean("ok", false)) return RedeemReply.Success(json.text("plan"), json.text("plan_until"), json.text("kind"))
        return RedeemReply.Failure(json?.text("error") ?: "http_${reply.first}")
    }

    /** An authorized call: after a 401 the token is refreshed once and the call retried once. Null: the server was not reached. */
    private fun authed(path: String, body: String): Pair<Int, String>? {
        val token = accessToken() ?: return null
        val first = post(path, body, token) ?: return null
        if (first.first != 401) return first
        val fresh = forceRefresh() ?: return first
        return post(path, body, fresh) ?: first
    }

    private fun signIn(): Session? {
        val reply = post("/auth/v1/signup", "{}", null) ?: return null
        if (reply.first !in 200..299) return null
        return parseSession(reply.second)?.also(store::save)
    }

    private fun refresh(session: Session): Session? {
        val reply = post("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", session.refreshToken).toString(), null)
            ?: return null
        if (reply.first in 200..299) return parseSession(reply.second, session.userId)?.also(store::save)
        // The server turned the refresh token down (not a timeout or a rate limit): this session cannot come back, start a new one.
        if (reply.first in 400..499 && reply.first != 408 && reply.first != 429) {
            store.save(null)
            return signIn()
        }
        return null
    }

    private fun parseSession(json: String, fallbackUserId: String = ""): Session? = try {
        val root = JSONObject(json)
        Session(
            accessToken = root.getString("access_token"),
            refreshToken = root.getString("refresh_token"),
            expiresAtMs = clock() + root.optLong("expires_in", 3600L) * 1000L,
            userId = root.optJSONObject("user")?.text("id") ?: fallbackUserId,
        )
    } catch (_: JSONException) {
        null
    }

    private fun post(path: String, body: String, bearer: String?): Pair<Int, String>? {
        val headers = mutableMapOf("apikey" to apiKey, "Content-Type" to "application/json")
        if (bearer != null) headers["Authorization"] = "Bearer $bearer"
        return try {
            http.send("POST", baseUrl + path, headers, body, TIMEOUT_MS)
        } catch (_: IOException) {
            null
        }
    }
}

/** The account of this app install, shared by the screens and the translation service (initialised in Services.init). */
object SubTvAccount {
    private const val TAG = "SubTvAccount"
    /** While a translation session runs the status is read again this often. */
    const val STATUS_INTERVAL_MS = 30 * 60 * 1000L
    private const val GATE_REFRESH_GAP_MS = 10_000L
    private const val KEY_STATUS_JSON = "subtv_status_json"
    private const val KEY_VERIFIED_MS = "subtv_status_verified_ms"
    private const val KEY_MAX_CLOCK_MS = "subtv_max_clock_ms"

    private lateinit var prefs: SharedPreferences
    private lateinit var client: SubTvAccountClient
    private var secrets: SecretStore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val statusFlow = MutableStateFlow<AccountStatus?>(null)
    /** The last account-status reply; loaded from encrypted cache until the fresh one arrives. */
    val status: StateFlow<AccountStatus?> = statusFlow

    private val gateFlow = MutableStateFlow<GateError?>(null)
    /** The last refusal of the translation gate; cleared whenever a fresh status arrives. */
    val gateBlock: StateFlow<GateError?> = gateFlow

    private val refreshingFlow = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = refreshingFlow

    private val numberFlow = MutableStateFlow<String?>(null)
    /** "Your account number" (8 characters), null before the first sign-in. */
    val accountNumber: StateFlow<String?> = numberFlow

    @Volatile private var lastRefreshAt = 0L
    @Volatile private var lastVerifiedAtMs = 0L

    fun init(context: Context) {
        if (::client.isInitialized) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences("subtv_account", Context.MODE_PRIVATE)
        val vault = runCatching { SecretStore(app) }.getOrNull()
        secrets = vault
        val cachedJson = runCatching { vault?.get(KEY_STATUS_JSON) }.getOrNull()
        val cachedVerified = runCatching { vault?.get(KEY_VERIFIED_MS)?.toLongOrNull() }.getOrNull() ?: 0L
        if (!cachedJson.isNullOrBlank()) {
            AccountStatus.parse(cachedJson)?.let {
                statusFlow.value = it
                lastVerifiedAtMs = cachedVerified
            }
        }
        effectiveNowMs()
        val (id, save) = AccountRules.deviceId(
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID), prefs.getString("device_id", null),
        ) { UUID.randomUUID().toString() }
        if (save) prefs.edit().putString("device_id", id).apply()
        client = SubTvAccountClient(Http.Default, PrefsSessionStore(prefs), id)
        numberFlow.value = AccountRules.accountNumber(client.userId())
    }

    /** Current wall time bounded below by the monotonic encrypted clock watermark. */
    fun effectiveNowMs(): Long {
        val sys = System.currentTimeMillis()
        val stored = runCatching { secrets?.get(KEY_MAX_CLOCK_MS)?.toLongOrNull() }.getOrNull() ?: 0L
        val best = AccountRules.effectiveNowMs(sys, stored)
        if (best > stored) runCatching { secrets?.put(KEY_MAX_CLOCK_MS, best.toString()) }
        return best
    }

    /** Strict gate for running translation (closes firewall + clock-backdate bypasses on local packs). */
    fun canTranslateVerifiedNow(): Boolean =
        !AccountRules.blocksTranslationStrict(statusFlow.value, gateFlow.value, effectiveNowMs(), lastVerifiedAtMs)

    fun needsOnlineVerification(): Boolean =
        statusFlow.value == null || !canTranslateVerifiedNow()

    fun deviceId(): String = client.deviceId

    /** For the translator (it runs on its own thread): a valid access token, signing in or refreshing when needed. */
    fun accessTokenBlocking(): String? = client.accessToken().also { syncNumber() }

    fun refreshTokenBlocking(): String? = client.forceRefresh().also { syncNumber() }

    suspend fun accessToken(): String? = withContext(Dispatchers.IO) { accessTokenBlocking() }

    /** Reads account-status and keeps it in [status]. False when it could not be read (the old status stays). */
    suspend fun refreshStatus(): Boolean = withContext(Dispatchers.IO) {
        refreshingFlow.value = true
        lastRefreshAt = System.currentTimeMillis()
        try {
            when (val reply = client.fetchStatus()) {
                is StatusReply.Ok -> {
                    val now = effectiveNowMs()
                    lastVerifiedAtMs = now
                    statusFlow.value = reply.status
                    gateFlow.value = null
                    if (reply.rawJson.isNotBlank()) {
                        runCatching {
                            secrets?.put(KEY_STATUS_JSON, reply.rawJson)
                            secrets?.put(KEY_VERIFIED_MS, now.toString())
                        }
                    }
                    true
                }
                is StatusReply.Failed -> {
                    Log.w(TAG, "account-status failed (${reply.http ?: "no connection"})")
                    false
                }
            }
        } finally {
            syncNumber()
            refreshingFlow.value = false
        }
    }

    /** Redeems a code; on success the status is read again so the cards and screens update. */
    suspend fun redeem(code: String): RedeemReply = withContext(Dispatchers.IO) {
        val reply = client.redeem(code)
        if (reply is RedeemReply.Success) refreshStatus()
        syncNumber()
        reply
    }

    /**
     * [redeem] in the account's own scope, with the answer posted on the main thread. For the screens: the subscribe screen
     * closes as soon as the status says the plan is active, and the answer must still come out (a screen scope would be cancelled).
     */
    fun redeemAsync(code: String, onDone: (RedeemReply) -> Unit) {
        scope.launch {
            val reply = redeem(code)
            Handler(Looper.getMainLooper()).post { onDone(reply) }
        }
    }

    /** The translator tells us why the server said no; an ended trial or plan also reads the status again (plans, dates). */
    fun onGateError(error: GateException) {
        gateFlow.value = error.error
        if (error.error == GateError.ACCESS_ENDED && System.currentTimeMillis() - lastRefreshAt > GATE_REFRESH_GAP_MS)
            scope.launch { refreshStatus() }
    }

    fun dismissedAnnouncement(): String = prefs.getString("dismissed_announcement", "").orEmpty()

    fun dismissAnnouncement(text: String) = prefs.edit().putString("dismissed_announcement", text).apply()

    private fun syncNumber() {
        numberFlow.value = AccountRules.accountNumber(client.userId())
    }
}
