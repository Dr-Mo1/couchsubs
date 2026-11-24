// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.reporting

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One scrubber for persisted reports and every outgoing text field. */
object ReportScrubber {
    private val credentials = Regex("""(?i)(\b(?:access_token|refresh_token|id_token|npsso|password|secret|token|code|key)\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s&#,;"'<>}]+)""")
    private val jsonCredentials = Regex("""(?i)("(?:access_token|refresh_token|id_token|npsso|password|secret|token|code|key)"\s*:\s*)"[^"]*"""")
    private val bearer = Regex("""(?i)\bBearer\s+[^\s"'<>;,]+""")
    // Try once per address candidate, rather than rescanning every suffix of a long log line.
    private val email = Regex("""(?<![\p{L}\p{N}._%+\-])[\p{L}\p{N}._%+\-]++@[\p{L}\p{N}.\-]+\.[\p{L}]{2,}""")
    private val longToken = Regex("""[A-Za-z0-9+/=_\-]{32,}""")
    fun scrub(text: String): String {
        var result = jsonCredentials.replace(text) { it.groupValues[1] + "\"***\"" }
        result = credentials.replace(result) { it.groupValues[1] + "***" }
        result = bearer.replace(result, "Bearer ***")
        result = email.replace(result, "***@***")
        return longToken.replace(result, "***")
    }
}

data class ReportInfo(val version: String, val versionCode: Int, val device: String, val android: String, val installId: String)

object ReportJson {
    fun build(
        kind: String,
        message: String,
        name: String?,
        log: String,
        info: ReportInfo,
        messageLimit: Int = 4000,
        media: List<String> = emptyList(),
    ): JSONObject =
        JSONObject().put("kind", kind).put("message", ReportScrubber.scrub(message).take(messageLimit).ifBlank { "Unknown problem" })
            .put("app_version", ReportScrubber.scrub(info.version)).put("version_code", info.versionCode)
            .put("device", ReportScrubber.scrub(info.device)).put("android", ReportScrubber.scrub(info.android))
            .put("install_id", info.installId).put("log", ReportScrubber.scrub(log).takeLast(200000)).apply {
                name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", ReportScrubber.scrub(it).take(80)) }
                val paths = media.map { it.trim() }.filter { it.isNotEmpty() }.take(ReportMedia.MAX_COUNT)
                if (paths.isNotEmpty()) put("media", JSONArray(paths))
            }
}

enum class MediaError { UNSUPPORTED_TYPE, TOO_LARGE }

object ReportMedia {
    const val MAX_COUNT = 3
    const val MAX_BYTES = 50L * 1024L * 1024L

    private val extensions = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "video/3gpp" to "3gp",
        "video/quicktime" to "mov",
    )

    fun normalizeMime(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() }

    fun extensionFor(mimeType: String?): String? =
        normalizeMime(mimeType)?.let(extensions::get)

    fun validate(mimeType: String?, sizeBytes: Long): MediaError? = when {
        extensionFor(mimeType) == null -> MediaError.UNSUPPORTED_TYPE
        sizeBytes < 0L || sizeBytes > MAX_BYTES -> MediaError.TOO_LARGE
        else -> null
    }

    fun objectPath(installId: String, mimeType: String, id: String = java.util.UUID.randomUUID().toString()): String? {
        val ext = extensionFor(mimeType) ?: return null
        val owner = installId.trim().trim('/')
        if (owner.isEmpty()) return null
        return "$owner/$id.$ext"
    }

    inline fun <T, K> uploadAll(
        items: List<T>,
        alreadyUploaded: Map<K, String>,
        key: (T) -> K,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        upload: (T) -> String?,
    ): Pair<Boolean, Map<K, String>> {
        val result = LinkedHashMap(alreadyUploaded)
        val pending = items.filter { key(it) !in result }
        for ((index, item) in pending.withIndex()) {
            onProgress(index + 1, pending.size)
            val path = upload(item) ?: return false to result
            result[key(item)] = path
        }
        return true to result
    }
}

object StartReport {
    fun message(versionName: String, versionCode: Int): String = "started $versionName ($versionCode)"

    fun buildIfNeeded(
        channel: String,
        lastReportedVersionCode: Int,
        versionName: String,
        versionCode: Int,
        info: ReportInfo,
    ): JSONObject? {
        if (channel != "beta" || versionCode <= 0 || lastReportedVersionCode == versionCode) return null
        return ReportJson.build("start", message(versionName, versionCode), null, "", info)
    }
}

/** Caller serializes access and persists a timestamp only after the report was saved. */
class ReportLimiter(private val clock: () -> Long = System::currentTimeMillis) {
    fun allowed(last: Long?): Boolean = last == null || clock() - last >= 30 * 60 * 1000L
    fun now(): Long = clock()
}

/** Complete files only; a crash during a write cannot leave partial JSON in the sending queue. */
class PendingReports(private val directory: File, private val cap: Int = 20) {
    @Synchronized fun files(): List<File> = directory.listFiles()?.filter { it.isFile && it.extension == "json" }
        ?.sortedBy { it.name }.orEmpty()
    @Synchronized fun save(json: JSONObject): File {
        directory.mkdirs()
        val stamp = maxOf(System.currentTimeMillis(), (files().lastOrNull()?.name?.substringBefore('-')?.toLongOrNull() ?: 0L) + 1)
        val file = File(directory, "%019d-%s.json".format(java.util.Locale.ROOT, stamp, java.util.UUID.randomUUID()))
        val temp = File(directory, file.name + ".tmp")
        temp.writeText(json.toString())
        check(temp.renameTo(file)) { "Cannot save report" }
        files().dropLast(cap).forEach { it.delete() }
        return file
    }
}

data class ReviewLine(val id: Long, val game: String, val source: String, val arabic: String, val origin: String, val shownAt: String)

object ReviewBatch {
    fun pending(lines: List<ReviewLine>, cursor: Long): List<List<ReviewLine>> =
        lines.filter { it.id > cursor }.sortedBy { it.id }.chunked(200)
    fun acknowledged(cursor: Long, batch: List<ReviewLine>, success: Boolean): Long =
        if (success) maxOf(cursor, batch.maxOfOrNull { it.id } ?: cursor) else cursor
    fun json(lines: List<ReviewLine>, info: ReportInfo): JSONArray = JSONArray().apply {
        for (line in lines) put(JSONObject().put("install_id", info.installId).put("game", ReportScrubber.scrub(line.game))
            .put("source_text", ReportScrubber.scrub(line.source).take(2000))
            .put("arabic_text", ReportScrubber.scrub(line.arabic).take(2000))
            .put("origin", ReportScrubber.scrub(line.origin).take(20)).put("shown_at", line.shownAt)
            .put("app_version", ReportScrubber.scrub(info.version)))
    }
}
