// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.account

import com.couchsubs.mobile.translation.ArabicStyleAssets
import com.couchsubs.mobile.translation.TargamgiGate
import com.couchsubs.mobile.translation.TranslationStyle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SecureDataStoreTest {
    private fun listJson(files: Map<String, ByteArray>): ByteArray {
        val array = JSONArray()
        for ((name, bytes) in files) {
            array.put(
                JSONObject()
                    .put("name", name)
                    .put("sha256", SecureDataRules.sha256(bytes))
                    .put("size", bytes.size)
                    .put("updated_at", "2026-11-26T12:00:00Z"),
            )
        }
        return JSONObject().put("files", array).toString().toByteArray(Charsets.UTF_8)
    }

    private fun sampleFiles(version: String = "v1"): MutableMap<String, ByteArray> = linkedMapOf(
        SecureDataRules.PACK_LIVE to "pack-live-$version".toByteArray(),
        SecureDataRules.PACK_CLEAN to "pack-clean-$version".toByteArray(),
        SecureDataRules.GLOSSARY_LIVE to "come on = يلا ($version)".toByteArray(),
        SecureDataRules.GLOSSARY_CLEAN to "come on = هيا ($version)".toByteArray(),
        SecureDataRules.CORRECTIONS_LIVE to "Run! = اجري يا سطى! ($version)".toByteArray(),
        SecureDataRules.CORRECTIONS_CLEAN to "Run! = اهرب! ($version)".toByteArray(),
        SecureDataRules.PROMPT_LIVE to "Street prompt $version".toByteArray(),
        SecureDataRules.PROMPT_CLEAN to "Clean prompt $version".toByteArray(),
    )

    @Test
    fun downloadDecisionOnlyPicksMissingOrChangedWhitelistedFiles() {
        val remote = listOf(
            RemotePackFile(SecureDataRules.PACK_LIVE, "aaa111"),
            RemotePackFile(SecureDataRules.GLOSSARY_LIVE, "BBB222"),
            RemotePackFile(SecureDataRules.CORRECTIONS_LIVE, "ccc333"),
            RemotePackFile("../evil.txt", "ddd444"),
            RemotePackFile("unknown.txt", "eee555"),
        )
        val local = mapOf(
            SecureDataRules.PACK_LIVE to "aaa111",
            SecureDataRules.GLOSSARY_LIVE to "bbb222", // case-insensitive match
            SecureDataRules.CORRECTIONS_LIVE to "oldhash",
        )

        val needed = SecureDataRules.filesToDownload(remote, local)
        assertEquals(listOf(SecureDataRules.CORRECTIONS_LIVE), needed.map { it.name })
    }

    @Test
    fun syncDownloadsAllChangedFilesVerifiesSha256AndSkipsUnchangedOnRefresh() {
        val dir = Files.createTempDirectory("secure-data-sync").toFile()
        try {
            val remote = sampleFiles("v1")
            val calls = mutableListOf<String>()
            var now = 1_000_000L
            val http = BinaryHttp { url, headers, _ ->
                calls += url
                assertEquals(TargamgiGate.PUBLISHABLE_KEY, headers["apikey"])
                assertEquals("Bearer tok-1", headers["Authorization"])
                when {
                    url.endsWith("/functions/v1/pack-gate?list=1") -> 200 to listJson(remote)
                    url.contains("?name=") -> {
                        val name = url.substringAfter("?name=")
                        remote[name]?.let { 200 to it } ?: (404 to ByteArray(0))
                    }
                    else -> 404 to ByteArray(0)
                }
            }
            val store = SecureDataStore(dir, http, token = { "tok-1" }, refresh = { null }, clock = { now })

            assertFalse(store.isReady(TranslationStyle.STREET))
            assertFalse(store.isReady(TranslationStyle.CLEAN))

            val first = store.sync(force = true)
            assertTrue(first is SecureDataSyncResult.Ok)
            assertEquals(8, (first as SecureDataSyncResult.Ok).downloaded.size)
            assertTrue(store.isReady(TranslationStyle.STREET))
            assertTrue(store.isReady(TranslationStyle.CLEAN))
            assertTrue(store.isReadyAll())
            // No leftover temp files.
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
            // ArabicStyleAssets reads from secure-data directory.
            val street = ArabicStyleAssets.load(dir, TranslationStyle.STREET)
            assertEquals("Street prompt v1", street.prompt)
            assertEquals("اجري يا سطى! (v1)", street.corrections["run"])

            // Within 12 hours, non-forced sync skips without any HTTP call.
            calls.clear()
            now += SecureDataRules.SYNC_INTERVAL_MS - 1_000L
            val skipped = store.sync(force = false)
            assertEquals(SecureDataSyncResult.Ok(emptyList(), skipped = true), skipped)
            assertTrue(calls.isEmpty())

            // After 12 hours, only the single changed file is re-downloaded.
            remote[SecureDataRules.GLOSSARY_LIVE] = "come on = يلا بينا".toByteArray()
            now += 2_000L
            val second = store.sync(force = false)
            assertEquals(SecureDataSyncResult.Ok(listOf(SecureDataRules.GLOSSARY_LIVE)), second)
            assertEquals(2, calls.size) // 1 list + 1 file download
            assertArrayEquals(remote[SecureDataRules.GLOSSARY_LIVE], File(dir, SecureDataRules.GLOSSARY_LIVE).readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptDownloadIsRejectedAndExistingFileIsUntouched() {
        val dir = Files.createTempDirectory("secure-data-corrupt").toFile()
        try {
            val original = "original-prompt".toByteArray()
            File(dir, SecureDataRules.PROMPT_LIVE).writeBytes(original)

            val expectedNew = "updated-prompt".toByteArray()
            val listPayload = listJson(mapOf(SecureDataRules.PROMPT_LIVE to expectedNew))
            val http = BinaryHttp { url, _, _ ->
                if (url.endsWith("?list=1")) 200 to listPayload
                else 200 to "corrupted-bytes".toByteArray()
            }
            val store = SecureDataStore(dir, http, token = { "tok" }, refresh = { null })

            val result = store.sync(force = true)
            assertEquals(SecureDataSyncResult.Partial(emptyList(), listOf(SecureDataRules.PROMPT_LIVE)), result)
            assertArrayEquals(original, File(dir, SecureDataRules.PROMPT_LIVE).readBytes())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a401RefreshesOnceAnd403EnsuresAccountBeforeReturningAccessInactive() {
        val dir = Files.createTempDirectory("secure-data-auth").toFile()
        try {
            val seenAuth = mutableListOf<String?>()
            var refreshes = 0
            val remote = mapOf(SecureDataRules.PROMPT_LIVE to "prompt-ok".toByteArray())
            val http401 = BinaryHttp { url, headers, _ ->
                seenAuth += headers["Authorization"]
                if (seenAuth.size == 1) 401 to """{"error":"unauthorized"}""".toByteArray()
                else if (url.endsWith("?list=1")) 200 to listJson(remote)
                else 200 to remote.getValue(SecureDataRules.PROMPT_LIVE)
            }
            val store401 = SecureDataStore(dir, http401, token = { "old" }, refresh = { refreshes++; "fresh" })
            val ok = store401.sync(force = true)
            assertTrue(ok is SecureDataSyncResult.Ok)
            assertEquals(1, refreshes)
            assertEquals("Bearer old", seenAuth[0])
            assertEquals("Bearer fresh", seenAuth[1])

            var ensured = 0
            val http403 = BinaryHttp { _, _, _ -> 403 to """{"error":"access_inactive"}""".toByteArray() }
            val store403 = SecureDataStore(dir, http403, token = { "tok" }, refresh = { null }, ensureAccount = { ensured++; true })
            assertEquals(SecureDataSyncResult.AccessInactive, store403.sync(force = true))
            assertEquals(1, ensured)

            val seenDeviceHeaders = mutableListOf<String?>()
            val httpDev = BinaryHttp { url, headers, _ ->
                seenDeviceHeaders += headers["x-device-id"]
                if (url.endsWith("?list=1")) 200 to listJson(remote)
                else 200 to remote.getValue(SecureDataRules.PROMPT_LIVE)
            }
            val storeDev = SecureDataStore(dir, httpDev, token = { "tok" }, refresh = { null }, deviceId = { "dev-bound-123" })
            assertTrue(storeDev.sync(force = true) is SecureDataSyncResult.Ok)
            assertEquals(listOf("dev-bound-123"), seenDeviceHeaders.distinct())
        } finally {
            dir.deleteRecursively()
        }
    }
}
