// SPDX-License-Identifier: AGPL-3.0-only
// Over-the-air update (sideloaded APK, no Play Store): manifest parsing, the version / "show again?" rules, the size and
// SHA-256 check, and the download itself. Pure JVM (no android.*), so UpdateTest covers all of it. The Android side
// (state, PackageInstaller session, dialog) is UpdateController.kt and ui/UpdateDialog.kt.
package com.couchsubs.mobile.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/** One release as published in update.json. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notesAr: String,
    val notesEn: String,
) {
    /** Notes in the UI language; falls back to the other language when one is empty. */
    fun notes(english: Boolean): String = if (english) notesEn.ifBlank { notesAr } else notesAr.ifBlank { notesEn }
}

enum class UpdateFailureKind { NETWORK, SIZE, HASH }

class UpdateException(
    val kind: UpdateFailureKind,
    message: String?,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : Exception(message, cause)

object UpdateChannel {
    const val STABLE = "stable"
    const val BETA = "beta"
    const val PREF_KEY = "updateChannel"

    fun normalize(value: String?): String = if (value == BETA) BETA else STABLE

    fun manifestUrl(stableUrl: String, channel: String): String {
        if (normalize(channel) != BETA) return stableUrl
        val idx = stableUrl.lastIndexOf("update.json")
        return if (idx >= 0) stableUrl.substring(0, idx) + "update-beta.json" + stableUrl.substring(idx + "update.json".length)
        else stableUrl
    }

    /**
     * Fetches the manifest for [channel]. On beta, tries update-beta.json first and falls back to [stableUrl]
     * if the beta manifest returns HTTP 404.
     */
    fun fetchManifest(
        stableUrl: String,
        channel: String,
        fetch: (String) -> String = { UpdateDownload.fetchText(it) },
    ): String {
        val url = manifestUrl(stableUrl, channel)
        if (url == stableUrl) return fetch(stableUrl)
        return try {
            fetch(url)
        } catch (error: UpdateException) {
            if (error.httpStatus == 404) fetch(stableUrl) else throw error
        }
    }
}

object BetaChannelToggle {
    const val TAPS_REQUIRED = 7

    data class Result(val count: Int, val channel: String, val toggled: Boolean)

    fun tap(currentCount: Int, currentChannel: String): Result {
        val normalized = UpdateChannel.normalize(currentChannel)
        val next = currentCount + 1
        return if (next >= TAPS_REQUIRED) {
            val switched = if (normalized == UpdateChannel.BETA) UpdateChannel.STABLE else UpdateChannel.BETA
            Result(count = 0, channel = switched, toggled = true)
        } else {
            Result(count = next, channel = normalized, toggled = false)
        }
    }
}

/**
 * Tracks unfinished app starts per versionCode in filesDir.
 * If the same versionCode fails to reach its first frame [THRESHOLD] or more times in a row,
 * the next launch enters rescue mode so the user can still install an update.
 */
object RescueMarker {
    const val FILE_NAME = "startup-marker.txt"
    const val THRESHOLD = 2

    data class State(val versionCode: Int, val count: Int)

    fun file(filesDir: File): File = File(filesDir, FILE_NAME)

    fun read(filesDir: File): State? {
        val f = file(filesDir)
        if (!f.isFile) return null
        val text = runCatching { f.readText(Charsets.UTF_8).trim() }.getOrNull() ?: return null
        val parts = text.split(':')
        if (parts.size != 2) return null
        val code = parts[0].toIntOrNull() ?: return null
        val count = parts[1].toIntOrNull() ?: return null
        if (code <= 0 || count < 0) return null
        return State(code, count)
    }

    /**
     * Called at the very start of Application.onCreate.
     * Returns true if this launch must enter rescue mode (2+ unfinished starts for [versionCode]).
     * Otherwise increments and persists the unfinished-start counter for [versionCode] and returns false.
     */
    fun onAppStart(filesDir: File, versionCode: Int): Boolean {
        val prev = read(filesDir)
        val unfinished = if (prev != null && prev.versionCode == versionCode) prev.count else 0
        if (unfinished >= THRESHOLD) return true
        write(filesDir, State(versionCode, unfinished + 1))
        return false
    }

    fun write(filesDir: File, state: State) {
        runCatching {
            filesDir.mkdirs()
            val target = file(filesDir)
            val tmp = File(filesDir, "$FILE_NAME.tmp")
            tmp.writeText("${state.versionCode}:${state.count}", Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText("${state.versionCode}:${state.count}", Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    fun clear(filesDir: File) {
        runCatching {
            file(filesDir).delete()
            File(filesDir, "$FILE_NAME.tmp").delete()
        }
    }
}

object UpdatePolicy {
    /** While the app stays open the manifest is re-checked this often (a cold start always checks). */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000
    const val MANIFEST_TIMEOUT_MS = 10_000
    const val DOWNLOAD_TIMEOUT_MS = 30_000
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 500L * 1024 * 1024

    private val SHA256 = Regex("[0-9a-f]{64}")

    /**
     * update.json -> [UpdateInfo]; null when a required field is missing or unsafe: positive versionCode, a name, an https
     * apkUrl, a 64-hex sha256 and a sane sizeBytes. The notes are optional.
     */
    fun parse(json: String): UpdateInfo? = try {
        val root = JSONObject(json)
        fun text(key: String) = if (root.isNull(key)) "" else root.optString(key).trim()
        val code = root.optLong("versionCode", 0L)
        val size = root.optLong("sizeBytes", 0L)
        val sha = text("sha256").lowercase(Locale.ROOT)
        val url = text("apkUrl")
        val name = text("versionName")
        val validUrl = url.startsWith("https://", ignoreCase = true) && URL(url).host.isNotBlank()
        if (code !in 1L..Int.MAX_VALUE || size !in 1L..MAX_APK_BYTES || !SHA256.matches(sha) || !validUrl || name.isBlank()) null
        else UpdateInfo(code.toInt(), name, url, sha, size, text("notesAr"), text("notesEn"))
    } catch (error: JSONException) {
        null
    } catch (error: IOException) { // MalformedURLException
        null
    }

    fun isNewer(info: UpdateInfo, installedCode: Int): Boolean = info.versionCode > installedCode

    /**
     * "Show again?" rule: a newer release is offered unless the user already tapped "Later" for it (or for a newer one)
     * since this cold start. A release newer than the dismissed one is still offered.
     */
    fun shouldShow(info: UpdateInfo, installedCode: Int, dismissedCode: Int): Boolean =
        isNewer(info, installedCode) && info.versionCode > dismissedCode

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** True when the file is exactly the published APK (size and SHA-256). */
    fun matches(file: File, info: UpdateInfo): Boolean =
        file.isFile && file.length() == info.sizeBytes && sha256Hex(file) == info.sha256

    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)

    /** Megabytes with one decimal and a dot, e.g. "12.5" (the progress line). */
    fun megabytes(bytes: Long): String = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)
}

object UpdateDownload {
    private const val USER_AGENT = "Targamgi-Android"

    /** GitHub answers release asset URLs with a redirect to objects.githubusercontent.com; HttpURLConnection follows it (https to https). */
    private fun open(url: String, timeoutMs: Int): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeoutMs
        readTimeout = timeoutMs
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("User-Agent", USER_AGENT)
    }

    /** Small text document (update.json); network problems come out as [UpdateException]. */
    fun fetchText(url: String, timeoutMs: Int = UpdatePolicy.MANIFEST_TIMEOUT_MS, maxBytes: Int = UpdatePolicy.MAX_MANIFEST_BYTES): String = try {
        val connection = open(url, timeoutMs)
        try {
            val status = connection.responseCode
            if (status != 200) throw UpdateException(UpdateFailureKind.NETWORK, "HTTP $status", httpStatus = status)
            String(connection.inputStream.use { readLimited(it, maxBytes) }, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    } catch (error: IOException) {
        throw UpdateException(UpdateFailureKind.NETWORK, error.message ?: error.javaClass.simpleName, error)
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > limit) throw IOException("too large")
        }
        return out.toByteArray()
    }

    /**
     * Downloads [info]'s APK to [target], checking size and SHA-256 while streaming. An already complete, matching file is
     * kept as is. On any problem nothing is left behind (partial file and bad file are deleted) and an [UpdateException]
     * says why; cancelling the coroutine also removes the partial file. [onProgress] gets the bytes written so far.
     */
    suspend fun downloadVerified(info: UpdateInfo, target: File, timeoutMs: Int = UpdatePolicy.DOWNLOAD_TIMEOUT_MS, onProgress: (Long) -> Unit = {}) {
        if (UpdatePolicy.matches(target, info)) {
            onProgress(info.sizeBytes)
            return
        }
        target.delete()
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            val connection = open(info.apkUrl, timeoutMs)
            try {
                val status = connection.responseCode
                if (status != 200) throw UpdateException(UpdateFailureKind.NETWORK, "HTTP $status", httpStatus = status)
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            done += count
                            // More bytes than published: stop right away instead of filling the cache.
                            if (done > info.sizeBytes) throw UpdateException(UpdateFailureKind.SIZE, "more than ${info.sizeBytes} bytes")
                            onProgress(done)
                        }
                    }
                }
                if (done != info.sizeBytes) throw UpdateException(UpdateFailureKind.SIZE, "$done of ${info.sizeBytes} bytes")
                if (UpdatePolicy.hex(digest.digest()) != info.sha256) throw UpdateException(UpdateFailureKind.HASH, "SHA-256 mismatch")
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                connection.disconnect()
            }
        } catch (error: IOException) {
            throw UpdateException(UpdateFailureKind.NETWORK, error.message ?: error.javaClass.simpleName, error)
        } finally {
            part.delete()
        }
    }
}
