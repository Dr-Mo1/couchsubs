// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.reporting

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Process
import com.couchsubs.mobile.BuildConfig
import com.couchsubs.mobile.Services
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

object ProblemReporter {
    private lateinit var prefs: SharedPreferences
    private lateinit var reports: PendingReports
    private lateinit var linesDir: File
    private lateinit var info: ReportInfo
    private val limiter = ReportLimiter()
    private val sendLock = Mutex()
    private val diskLock = Any()
    private val errorLock = Any()
    private var lineSequenceReady = false
    val configured: Boolean get() = BuildConfig.REPORTS_URL.isNotBlank() && BuildConfig.REPORTS_KEY.isNotBlank()
    private val ready: Boolean get() = ::prefs.isInitialized && ::info.isInitialized
    var automatic: Boolean
        get() = ready && prefs.getBoolean("automaticReports", true)
        set(value) {
            synchronized(errorLock) { prefs.edit().putBoolean("automaticReports", value).commit() }
        }
    var improveTranslation: Boolean
        get() = ready && prefs.getBoolean("reviewTranslation", true)
        set(value) {
            synchronized(diskLock) {
                prefs.edit().putBoolean("reviewTranslation", value).commit()
                if (!value) linesDir.listFiles()?.forEach { it.delete() }
            }
        }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val id = prefs.getString("reportInstallId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("reportInstallId", it).commit()
        }
        info = ReportInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, "${Build.MANUFACTURER} ${Build.MODEL}",
            "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", id)
        reports = PendingReports(File(context.filesDir, "reports"))
        linesDir = File(context.filesDir, "review-lines")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                runCatching { com.couchsubs.mobile.account.ActivityLog.error(error, synchronous = true) }
                if (automatic) reports.save(ReportJson.build("crash", error.stackTraceToString(), null, logTail(), info, 8000))
            } catch (_: Throwable) {
                // Reporting must never prevent the platform crash handler from running.
            } finally {
                previous?.uncaughtException(thread, error) ?: run { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
            }
        }
        flush()
    }

    fun start(channel: String) {
        if (!ready || !configured || channel != "beta") return
        Services.scope.launch(Dispatchers.IO) {
            runCatching {
                val recorded = synchronized(errorLock) {
                    val last = prefs.getInt("startReportVersionCode", 0)
                    val json = StartReport.buildIfNeeded(channel, last, info.version, info.versionCode, info)
                        ?: return@synchronized false
                    reports.save(json)
                    prefs.edit().putInt("startReportVersionCode", info.versionCode).commit()
                    true
                }
                if (recorded) sendPending()
            }
        }
    }

    fun error(where: String, detail: String?) {
        if (!automatic) return
        Services.scope.launch(Dispatchers.IO) {
            runCatching {
                val key = "reportLimit.$where"
                val initial = if (prefs.contains(key)) prefs.getLong(key, 0) else null
                if (!limiter.allowed(initial)) return@runCatching
                val log = logTail()
                val recorded = synchronized(errorLock) {
                    if (!automatic) return@synchronized false
                    val last = if (prefs.contains(key)) prefs.getLong(key, 0) else null
                    if (!limiter.allowed(last)) return@synchronized false
                    reports.save(ReportJson.build("error", "$where: ${detail.orEmpty()}", null, log, info))
                    prefs.edit().putLong(key, limiter.now()).commit()
                    true
                }
                if (recorded) sendPending()
            }
        }
    }

    /** Stream a single user attachment to Supabase Storage without loading it into memory. */
    suspend fun uploadMedia(context: Context, uri: Uri, mimeType: String, sizeBytes: Long): String? = withContext(Dispatchers.IO) {
        try {
            if (!ready || !configured || ReportMedia.validate(mimeType, sizeBytes) != null) return@withContext null
            val normalizedMime = ReportMedia.normalizeMime(mimeType) ?: return@withContext null
            val objectPath = ReportMedia.objectPath(info.installId, normalizedMime) ?: return@withContext null
            val connection = URL("${BuildConfig.REPORTS_URL.trimEnd('/')}/storage/v1/object/report-media/$objectPath").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(sizeBytes)
                connection.setRequestProperty("apikey", BuildConfig.REPORTS_KEY)
                connection.setRequestProperty("Content-Type", normalizedMime)
                if (BuildConfig.REPORTS_KEY.startsWith("eyJ")) connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.REPORTS_KEY}")
                val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
                input.use { stream ->
                    connection.outputStream.use { out ->
                        val buffer = ByteArray(16 * 1024)
                        var written = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            out.write(buffer, 0, count)
                            written += count
                        }
                        if (written != sizeBytes) return@withContext null
                    }
                }
                if (connection.responseCode == 200) objectPath else null
            } finally { connection.disconnect() }
        } catch (_: Exception) { null }
    }

    /** Save first, then send. A retry reuses the same pending file rather than adding another copy. */
    suspend fun user(
        message: String,
        name: String,
        attachLog: Boolean,
        pending: File? = null,
        media: List<String> = emptyList(),
    ): Pair<Boolean, File?> = withContext(Dispatchers.IO) {
        var file = pending
        try {
            if (!configured || message.isBlank()) return@withContext false to file
            sendLock.withLock {
                var old = file
                // Distinguish a delivered report from a file dropped by the queue cap.
                if (old != null && !old.exists()) {
                    if (prefs.getStringSet("deliveredReportFiles", emptySet()).orEmpty().contains(old.name)) return@withLock true to null
                    file = null
                    old = null
                }
                val json = ReportJson.build("user", message, name, if (attachLog) logTail() else "", info, media = media)
                if (old == null) file = reports.save(json) else {
                    val temp = File(old.path + ".tmp")
                    temp.writeText(json.toString())
                    check(temp.renameTo(old))
                }
                val saved = checkNotNull(file)
                val success = sendReport(saved)
                success to if (success) null else saved
            }
        } catch (_: Exception) { false to file }
    }

    /** Capture immutable values at the History call site; file IO runs on the existing history worker. */
    fun recordLine(game: String, source: String, arabic: String, origin: String, shownAt: Long) {
        if (!improveTranslation || !configured || arabic.isBlank()) return
        synchronized(diskLock) {
            if (!improveTranslation) return
            linesDir.mkdirs()
            if (!lineSequenceReady) {
                // Recover after a crash between the file rename and saving the sequence, off the UI thread.
                val highest = linesDir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }?.maxOrNull() ?: 0L
                if (highest > prefs.getLong("reviewNextId", 0)) prefs.edit().putLong("reviewNextId", highest).commit()
                lineSequenceReady = true
            }
            val id = maxOf(prefs.getLong("reviewNextId", 0), prefs.getLong("reviewCursor", 0)) + 1
            val line = ReviewLine(id, game, source, arabic, origin, Instant.ofEpochMilli(shownAt).toString())
            val json = ReviewBatch.json(listOf(line), info).getJSONObject(0).put("id", id)
            val file = File(linesDir, "$id.json")
            val temp = File(linesDir, "$id.tmp")
            temp.writeText(json.toString())
            check(temp.renameTo(file))
            prefs.edit().putLong("reviewNextId", id).commit()
        }
    }

    fun flush() {
        Services.scope.launch(Dispatchers.IO) { runCatching { sendPending(); sendLines() } }
    }

    private suspend fun sendPending() = sendLock.withLock {
        if (!configured) return@withLock
        for (file in reports.files()) {
            val kind = runCatching { JSONObject(file.readText()).getString("kind") }.getOrNull() ?: continue
            if (kind != "user" && kind != "start" && !automatic) continue
            if (!sendReport(file)) break
        }
    }

    private fun sendReport(file: File): Boolean = runCatching {
        val json = JSONObject(file.readText())
        // Local crash traces may be 8000 characters; the server message column accepts 4000.
        json.put("message", ReportScrubber.scrub(json.getString("message")).take(4000))
        if (!post("reports", json.toString())) false else {
            val delivered = (prefs.getStringSet("deliveredReportFiles", emptySet()).orEmpty() + file.name).sorted().takeLast(20).toSet()
            if (prefs.edit().putStringSet("deliveredReportFiles", delivered).commit()) file.delete()
            true
        }
    }.getOrDefault(false)

    private suspend fun sendLines() = sendLock.withLock {
        if (!configured || !improveTranslation) return@withLock
        val entries = synchronized(diskLock) {
            val cursor = prefs.getLong("reviewCursor", 0)
            linesDir.listFiles()?.mapNotNull { file ->
                val id = file.nameWithoutExtension.toLongOrNull()
                if (file.extension != "json" || id == null || id <= cursor) null else runCatching {
                    val json = JSONObject(file.readText())
                    ReviewLine(id, json.getString("game"), json.getString("source_text"), json.getString("arabic_text"),
                        json.getString("origin"), json.getString("shown_at"))
                }.getOrNull()
            }.orEmpty()
        }
        for (batch in ReviewBatch.pending(entries, prefs.getLong("reviewCursor", 0))) {
            if (!improveTranslation || !post("lines", ReviewBatch.json(batch, info).toString())) break
            synchronized(diskLock) {
                val cursor = ReviewBatch.acknowledged(prefs.getLong("reviewCursor", 0), batch, true)
                // Persist acknowledgement before removing files, so restart cannot resend acknowledged rows.
                if (prefs.edit().putLong("reviewCursor", cursor).commit()) {
                    linesDir.listFiles()?.filter { it.extension == "json" && (it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE) <= cursor }
                        ?.forEach { it.delete() }
                }
            }
        }
    }

    private fun post(table: String, body: String): Boolean {
        if (!configured) return false
        val connection = URL("${BuildConfig.REPORTS_URL.trimEnd('/')}/rest/v1/$table").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doOutput = true
            connection.setRequestProperty("apikey", BuildConfig.REPORTS_KEY)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Prefer", "return=minimal")
            if (BuildConfig.REPORTS_KEY.startsWith("eyJ")) connection.setRequestProperty("Authorization", "Bearer ${BuildConfig.REPORTS_KEY}")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode == 201
        } finally { connection.disconnect() }
    }

    private fun logTail(): String =
        ReportLog.combine(com.couchsubs.mobile.core.Log.readAll(ReportLog.MAX_CHARS), logcatTail())

    private fun logcatTail(): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "600", "--pid", Process.myPid().toString()))
        val result = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val tail = StringBuilder()
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        tail.append(buffer, 0, count)
                        if (tail.length > ReportLog.MAX_CHARS) tail.delete(0, tail.length - ReportLog.MAX_CHARS)
                    }
                    result.set(tail.toString())
                }
            }
        }, "Problem log tail").apply { isDaemon = true; start() }
        try {
            reader.join(2000)
            if (!reader.isAlive && process.waitFor(100, TimeUnit.MILLISECONDS) && process.exitValue() == 0)
                result.get().orEmpty() else ""
        } finally { process.destroy(); reader.interrupt() }
    } catch (_: Exception) { "" }
}
