// SPDX-License-Identifier: AGPL-3.0-only
// 1.0.48 part B: Arabic packs, glossaries, corrections and prompts live off the APK and are downloaded after sign-in
// through pack-gate into the app's private filesDir/secure-data/ folder. Every file is SHA-256 verified and written
// atomically (temp file + rename).
package com.couchsubs.mobile.account

import android.content.Context
import com.couchsubs.mobile.core.Log
import com.couchsubs.mobile.translation.TargamgiGate
import com.couchsubs.mobile.translation.TranslationStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** One file listed by GET /functions/v1/pack-gate?list=1. */
data class RemotePackFile(
    val name: String,
    val sha256: String,
    val size: Long = 0L,
    val updatedAt: String? = null,
)

/** Binary HTTP GET helper (plain Kotlin so SecureDataStore is JVM-testable with a fake). */
fun interface BinaryHttp {
    fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Pair<Int, ByteArray>

    companion object {
        val Default = BinaryHttp { url, headers, timeoutMs ->
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-store")
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                status to (stream?.use { it.readBytes() } ?: ByteArray(0))
            } finally {
                connection.disconnect()
            }
        }
    }
}

/** Pure rules for filesDir/secure-data/ (JVM-tested). */
object SecureDataRules {
    const val DIR_NAME = "secure-data"
    /** Refresh check interval while the app stays open (12 hours). */
    const val SYNC_INTERVAL_MS = 12L * 60 * 60 * 1000

    const val PACK_LIVE = "gta-6-ar.json.gz"
    const val PACK_CLEAN = "gta-6-ar-clean.json.gz"
    const val GLOSSARY_LIVE = "glossary-ar.txt"
    const val GLOSSARY_CLEAN = "glossary-ar-clean.txt"
    const val CORRECTIONS_LIVE = "corrections-ar.txt"
    const val CORRECTIONS_CLEAN = "corrections-ar-clean.txt"
    const val PROMPT_LIVE = "arabic-system-prompt-live.txt"
    const val PROMPT_CLEAN = "arabic-system-prompt-clean.txt"

    val STREET_FILES = listOf(PACK_LIVE, GLOSSARY_LIVE, CORRECTIONS_LIVE, PROMPT_LIVE)
    val CLEAN_FILES = listOf(PACK_CLEAN, GLOSSARY_CLEAN, CORRECTIONS_CLEAN, PROMPT_CLEAN)
    val ALL_FILES = STREET_FILES + CLEAN_FILES
    private val ALLOWED = ALL_FILES.toSet()

    fun filesFor(style: TranslationStyle): List<String> =
        if (style == TranslationStyle.CLEAN) CLEAN_FILES else STREET_FILES

    fun packFileFor(style: TranslationStyle): String =
        if (style == TranslationStyle.CLEAN) PACK_CLEAN else PACK_LIVE

    fun isSafeFileName(name: String): Boolean =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains("..") && name in ALLOWED

    fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun sha256(file: File): String? {
        if (!file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: IOException) {
            null
        }
    }

    /** Only whitelisted files whose sha256 differs from the verified file on disk need downloading. */
    fun filesToDownload(remote: List<RemotePackFile>, localHashes: Map<String, String>): List<RemotePackFile> =
        remote.filter { file ->
            isSafeFileName(file.name) &&
                file.sha256.isNotBlank() &&
                !localHashes[file.name].equals(file.sha256, ignoreCase = true)
        }

    /** Parses `{files:[{name, sha256, size, updated_at}]}`; null on malformed JSON. */
    fun parseFileList(json: String): List<RemotePackFile>? = try {
        val root = JSONObject(json)
        val array = root.optJSONArray("files") ?: return null
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("name", "").trim()
            val hash = obj.optString("sha256", "").trim()
            if (name.isEmpty() || hash.isEmpty()) null
            else RemotePackFile(
                name = name,
                sha256 = hash,
                size = obj.optLong("size", 0L),
                updatedAt = if (obj.isNull("updated_at")) null else obj.optString("updated_at").ifBlank { null },
            )
        }
    } catch (_: JSONException) {
        null
    }
}

sealed interface SecureDataSyncResult {
    data class Ok(val downloaded: List<String>, val skipped: Boolean = false) : SecureDataSyncResult
    data class Partial(val downloaded: List<String>, val failed: List<String>) : SecureDataSyncResult
    data object AccessInactive : SecureDataSyncResult
    data class Failed(val http: Int? = null) : SecureDataSyncResult
}

/**
 * Downloads and verifies the Arabic dictionary/prompt/glossary/corrections files via `pack-gate` into [directory].
 * Plain Kotlin so all download, hash verification and atomic write decisions are JVM-testable.
 */
class SecureDataStore(
    val directory: File,
    private val http: BinaryHttp,
    private val token: () -> String?,
    private val refresh: () -> String?,
    private val ensureAccount: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val baseUrl: String = TargamgiGate.SUPABASE_URL,
    private val apiKey: String = TargamgiGate.PUBLISHABLE_KEY,
) {
    companion object {
        const val LIST_TIMEOUT_MS = 15_000
        const val DOWNLOAD_TIMEOUT_MS = 60_000
        private const val SYNC_STATE_FILE = ".sync-state"
    }

    private val lock = Any()

    fun file(name: String): File = File(directory, name)

    /** True when all required files for [style] exist and are non-empty on disk. */
    fun isReady(style: TranslationStyle): Boolean =
        SecureDataRules.filesFor(style).all { name -> file(name).let { it.isFile && it.length() > 0L } }

    /** True when all 8 files exist and are non-empty on disk. */
    fun isReadyAll(): Boolean =
        SecureDataRules.ALL_FILES.all { name -> file(name).let { it.isFile && it.length() > 0L } }

    /** Computes current SHA-256 hashes of all whitelisted files present on disk. */
    fun localHashes(): Map<String, String> = synchronized(lock) { localHashesLocked() }

    private fun localHashesLocked(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (name in SecureDataRules.ALL_FILES) {
            val hash = SecureDataRules.sha256(file(name))
            if (hash != null) result[name] = hash
        }
        return result
    }

    /**
     * Checks `pack-gate?list=1` and downloads only files whose SHA-256 changed. Each file is verified against its
     * listed SHA-256, written to a `.tmp` file, and atomically renamed into place.
     */
    fun sync(force: Boolean = false, maxAgeMs: Long = SecureDataRules.SYNC_INTERVAL_MS): SecureDataSyncResult = synchronized(lock) {
        val lastSync = readLastSync()
        if (!force && isReadyAll() && lastSync > 0L && clock() - lastSync < maxAgeMs) {
            return SecureDataSyncResult.Ok(emptyList(), skipped = true)
        }

        val listReply = authedGet("/functions/v1/pack-gate?list=1", LIST_TIMEOUT_MS, retryAccountOn403 = true)
            ?: return SecureDataSyncResult.Failed(null)
        if (listReply.first == 403) return SecureDataSyncResult.AccessInactive
        if (listReply.first !in 200..299) return SecureDataSyncResult.Failed(listReply.first)

        val remote = SecureDataRules.parseFileList(String(listReply.second, Charsets.UTF_8))
            ?: return SecureDataSyncResult.Failed(listReply.first)

        val local = localHashesLocked()
        val needed = SecureDataRules.filesToDownload(remote, local)
        if (needed.isEmpty()) {
            writeLastSync(clock())
            return SecureDataSyncResult.Ok(emptyList())
        }

        directory.mkdirs()
        val downloaded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (item in needed) {
            val encoded = URLEncoder.encode(item.name, "UTF-8")
            val reply = authedGet("/functions/v1/pack-gate?name=$encoded", DOWNLOAD_TIMEOUT_MS, retryAccountOn403 = false)
            if (reply == null) {
                failed += item.name
                continue
            }
            if (reply.first == 403) return SecureDataSyncResult.AccessInactive
            if (reply.first !in 200..299) {
                failed += item.name
                continue
            }
            val data = reply.second
            if (!SecureDataRules.sha256(data).equals(item.sha256, ignoreCase = true)) {
                failed += item.name
                continue
            }
            try {
                writeAtomically(file(item.name), data)
                downloaded += item.name
            } catch (_: IOException) {
                failed += item.name
            }
        }

        if (failed.isEmpty()) {
            writeLastSync(clock())
            SecureDataSyncResult.Ok(downloaded)
        } else {
            SecureDataSyncResult.Partial(downloaded, failed)
        }
    }

    private fun authedGet(path: String, timeoutMs: Int, retryAccountOn403: Boolean): Pair<Int, ByteArray>? {
        val currentToken = token() ?: return null
        val first = get(path, currentToken, timeoutMs) ?: return null
        if (first.first == 401) {
            val fresh = refresh() ?: return first
            return get(path, fresh, timeoutMs) ?: first
        }
        // A brand-new anonymous user has no `accounts` row until account-status runs; ensure it once before failing 403.
        if (first.first == 403 && retryAccountOn403 && ensureAccount()) {
            val fresh = token() ?: return first
            return get(path, fresh, timeoutMs) ?: first
        }
        return first
    }

    private fun get(path: String, bearer: String, timeoutMs: Int): Pair<Int, ByteArray>? = try {
        http.get(
            baseUrl.trimEnd('/') + path,
            mapOf("apikey" to apiKey, "Authorization" to "Bearer $bearer"),
            timeoutMs,
        )
    } catch (_: IOException) {
        null
    }

    private fun writeAtomically(target: File, data: ByteArray) {
        val temp = File(directory, "${target.name}.tmp")
        try {
            temp.writeBytes(data)
            if (!temp.renameTo(target)) {
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) throw IOException("Failed to rename ${temp.name} to ${target.name}")
            }
        } catch (error: IOException) {
            temp.delete()
            throw error
        }
    }

    private fun readLastSync(): Long {
        val state = File(directory, SYNC_STATE_FILE)
        if (!state.isFile) return 0L
        return runCatching { state.readText(Charsets.UTF_8).trim().toLong() }.getOrDefault(0L)
    }

    private fun writeLastSync(timestampMs: Long) {
        directory.mkdirs()
        runCatching { writeAtomically(File(directory, SYNC_STATE_FILE), timestampMs.toString().toByteArray(Charsets.UTF_8)) }
    }
}

/** Process-wide holder for `filesDir/secure-data/`, shared by Services, TranslationService and the UI. */
object SecureData {
    private const val TAG = "SubTvSecureData"

    enum class SyncState { IDLE, SYNCING, READY, FAILED, ACCESS_INACTIVE }

    private lateinit var store: SecureDataStore
    @Volatile private var onDataUpdated: (() -> Unit)? = null

    private val stateFlow = MutableStateFlow(SyncState.IDLE)
    val state: StateFlow<SyncState> = stateFlow

    fun directory(context: Context): File = File(context.applicationContext.filesDir, SecureDataRules.DIR_NAME)

    fun init(context: Context, onUpdated: (() -> Unit)? = null) {
        if (::store.isInitialized) return
        onDataUpdated = onUpdated
        store = SecureDataStore(
            directory = directory(context),
            http = BinaryHttp.Default,
            token = SubTvAccount::accessTokenBlocking,
            refresh = SubTvAccount::refreshTokenBlocking,
            ensureAccount = { runBlocking { SubTvAccount.refreshStatus() } },
        )
        if (store.isReadyAll()) stateFlow.value = SyncState.READY
    }

    fun isReady(style: TranslationStyle): Boolean = ::store.isInitialized && store.isReady(style)

    fun isReadyAll(): Boolean = ::store.isInitialized && store.isReadyAll()

    fun packFile(context: Context, style: TranslationStyle): File =
        File(directory(context), SecureDataRules.packFileFor(style))

    /** Deletes all locally cached dictionary/prompt files (called when access expires or tampering is detected). */
    fun wipe(context: Context) {
        val dir = directory(context)
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        stateFlow.value = SyncState.ACCESS_INACTIVE
    }

    fun syncBlocking(force: Boolean = false): SecureDataSyncResult {
        if (!::store.isInitialized) return SecureDataSyncResult.Failed(null)
        stateFlow.value = SyncState.SYNCING
        val result = store.sync(force = force)
        when (result) {
            is SecureDataSyncResult.Ok -> {
                stateFlow.value = if (store.isReadyAll()) SyncState.READY else SyncState.IDLE
                if (result.downloaded.isNotEmpty()) {
                    Log.i(TAG, "downloaded ${result.downloaded.size} secure-data files: ${result.downloaded.joinToString()}")
                    onDataUpdated?.invoke()
                }
            }
            is SecureDataSyncResult.Partial -> {
                stateFlow.value = SyncState.FAILED
                Log.w(TAG, "secure-data partial sync; downloaded=${result.downloaded}, failed=${result.failed}")
                if (result.downloaded.isNotEmpty()) onDataUpdated?.invoke()
            }
            SecureDataSyncResult.AccessInactive -> {
                runCatching { store.directory.listFiles()?.forEach { it.delete() } }
                stateFlow.value = SyncState.ACCESS_INACTIVE
                Log.i(TAG, "pack-gate refused: access_inactive (cached packs wiped)")
            }
            is SecureDataSyncResult.Failed -> {
                stateFlow.value = if (store.isReadyAll()) SyncState.READY else SyncState.FAILED
                Log.w(TAG, "secure-data sync failed (${result.http ?: "offline"})")
            }
        }
        return result
    }

    suspend fun sync(force: Boolean = false): SecureDataSyncResult = withContext(Dispatchers.IO) {
        syncBlocking(force = force)
    }
}
