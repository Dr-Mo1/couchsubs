// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.update

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest

class UpdateTest {
    private val apk = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private lateinit var server: TinyServer
    private lateinit var base: String
    private lateinit var dir: File

    /** Smallest HTTP/1.0 server: path -> (status, location header, body). java.net only (com.sun.net.httpserver is not on the Android test classpath). */
    private class TinyServer {
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val routes = java.util.concurrent.ConcurrentHashMap<String, Triple<Int, String?, ByteArray>>()
        val port get() = socket.localPort

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (error: java.io.IOException) { break }
                    Thread { serve(client) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun serve(client: java.net.Socket) {
            client.use {
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val (status, location, body) = routes[path] ?: Triple(404, null, ByteArray(0))
                val head = "HTTP/1.0 $status X\r\nContent-Length: ${body.size}\r\n" + (location?.let { l -> "Location: $l\r\n" } ?: "") + "\r\n"
                it.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
            }
        }

        fun stop() = socket.close()
    }

    @Before
    fun start() {
        dir = Files.createTempDirectory("update-test").toFile()
        server = TinyServer()
        // GitHub answers the release URL with a redirect; /apk redirects to /real like objects.githubusercontent.com.
        server.routes["/apk"] = Triple(302, "/real", ByteArray(0))
        server.routes["/real"] = Triple(200, null, apk)
        server.routes["/short"] = Triple(200, null, apk.copyOf(1000))
        server.routes["/long"] = Triple(200, null, apk + byteArrayOf(1, 2, 3))
        server.routes["/flipped"] = Triple(200, null, apk.copyOf().also { copy -> copy[0] = (copy[0] + 1).toByte() })
        server.routes["/gone"] = Triple(404, null, ByteArray(0))
        server.routes["/update.json"] = Triple(200, null, manifest().toByteArray())
        base = "http://127.0.0.1:${server.port}"
    }

    @After
    fun stop() {
        server.stop()
        dir.deleteRecursively()
    }

    private fun manifest(code: Int = 38, sha: String = apkSha, url: String = "https://example.com/Targamgi-0.1.0-beta.$code.apk", size: Long = apk.size.toLong(),
                         extra: String = ""","notesAr":"إصلاحات","notesEn":"Fixes"""") =
        """{"versionCode":$code,"versionName":"0.1.0-beta.$code","apkUrl":"$url","sha256":"$sha","sizeBytes":$size$extra}"""

    private fun info(path: String, sha: String = apkSha, size: Long = apk.size.toLong()) =
        UpdateInfo(38, "0.1.0-beta.38", "$base$path", sha, size, "", "")

    // ---- manifest ----

    @Test
    fun parsesTheDocumentedManifest() {
        val info = UpdatePolicy.parse(manifest(sha = apkSha.uppercase()))!!
        assertEquals(38, info.versionCode)
        assertEquals("0.1.0-beta.38", info.versionName)
        assertEquals("https://example.com/Targamgi-0.1.0-beta.38.apk", info.apkUrl)
        assertEquals(apkSha, info.sha256) // upper case hex is normalised
        assertEquals(apk.size.toLong(), info.sizeBytes)
        assertEquals("إصلاحات", info.notesAr)
        assertEquals("Fixes", info.notesEn)
    }

    @Test
    fun notesFollowTheUiLanguageAndFallBack() {
        val both = UpdatePolicy.parse(manifest())!!
        assertEquals("إصلاحات", both.notes(english = false))
        assertEquals("Fixes", both.notes(english = true))
        val onlyEnglish = UpdatePolicy.parse(manifest(extra = ""","notesEn":"Fixes","notesAr":null"""))!!
        assertEquals("Fixes", onlyEnglish.notes(english = false))
        assertEquals("", UpdatePolicy.parse(manifest(extra = ""))!!.notes(english = false))
    }

    @Test
    fun rejectsIncompleteOrUnsafeManifests() {
        assertNull(UpdatePolicy.parse("not json"))
        assertNull(UpdatePolicy.parse("{}"))
        assertNull(UpdatePolicy.parse(manifest(code = 0)))
        assertNull(UpdatePolicy.parse(manifest(sha = "abc")))
        assertNull(UpdatePolicy.parse(manifest(sha = "z".repeat(64))))
        assertNull(UpdatePolicy.parse(manifest(size = 0)))
        assertNull(UpdatePolicy.parse(manifest(size = UpdatePolicy.MAX_APK_BYTES + 1)))
        assertNull(UpdatePolicy.parse(manifest(url = "http://example.com/a.apk"))) // plain http is never accepted
        assertNull(UpdatePolicy.parse(manifest(url = "file:///sdcard/a.apk")))
        assertNull(UpdatePolicy.parse(manifest(url = "https://")))
        assertNull(UpdatePolicy.parse(manifest().replace("\"versionName\":\"0.1.0-beta.38\",", "")))
        assertNotNull(UpdatePolicy.parse(manifest(extra = ""))) // notes are optional
    }

    // ---- version and "show again?" rule ----

    @Test
    fun onlyAHigherVersionCodeIsNewer() {
        val info = UpdatePolicy.parse(manifest(code = 38))!!
        assertTrue(UpdatePolicy.isNewer(info, 37))
        assertFalse(UpdatePolicy.isNewer(info, 38))
        assertFalse(UpdatePolicy.isNewer(info, 39))
    }

    @Test
    fun laterHidesThatReleaseUntilTheNextColdStart() {
        val v38 = UpdatePolicy.parse(manifest(code = 38))!!
        val v39 = UpdatePolicy.parse(manifest(code = 39))!!
        assertTrue(UpdatePolicy.shouldShow(v38, installedCode = 37, dismissedCode = 0))
        assertFalse(UpdatePolicy.shouldShow(v38, installedCode = 37, dismissedCode = 38)) // "Later" tapped
        assertTrue(UpdatePolicy.shouldShow(v39, installedCode = 37, dismissedCode = 38)) // a newer release is still offered
        assertFalse(UpdatePolicy.shouldShow(v38, installedCode = 38, dismissedCode = 0)) // already installed
        assertTrue(UpdatePolicy.shouldShow(v38, installedCode = 37, dismissedCode = 0)) // cold start resets the dismissal
    }

    @Test
    fun failedDownloadCloseNeverSuppressesTheNextPrompt() {
        val v47 = UpdatePolicy.parse(manifest(code = 47))!!
        // Closing an error dialog (failed = true) keeps dismissedCode untouched, so the next check still prompts.
        val afterFailure = UpdatePolicy.dismissAfterClose(currentDismissed = 0, versionCode = v47.versionCode, failed = true)
        assertEquals(0, afterFailure)
        assertTrue(UpdatePolicy.shouldShow(v47, installedCode = 42, dismissedCode = afterFailure))

        // Even if an older release (46) had been deferred earlier, failing on 47 does not suppress 47.
        val afterFailureWithOlder = UpdatePolicy.dismissAfterClose(currentDismissed = 46, versionCode = v47.versionCode, failed = true)
        assertEquals(46, afterFailureWithOlder)
        assertTrue(UpdatePolicy.shouldShow(v47, installedCode = 42, dismissedCode = afterFailureWithOlder))

        // Explicit "Later" (failed = false) suppresses that version until cold start.
        val afterLater = UpdatePolicy.dismissAfterClose(currentDismissed = 0, versionCode = v47.versionCode, failed = false)
        assertEquals(47, afterLater)
        assertFalse(UpdatePolicy.shouldShow(v47, installedCode = 42, dismissedCode = afterLater))
    }

    @Test
    fun progressText() {
        assertEquals(0, UpdatePolicy.percent(5, 0))
        assertEquals(50, UpdatePolicy.percent(50, 100))
        assertEquals(100, UpdatePolicy.percent(200, 100))
        assertEquals("12.5", UpdatePolicy.megabytes(13_107_200))
        assertEquals("0.0", UpdatePolicy.megabytes(0))
    }

    // ---- SHA-256 ----

    @Test
    fun sha256OfKnownBytes() {
        val file = File(dir, "abc.bin").apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdatePolicy.sha256Hex(file))
        val published = UpdateInfo(1, "x", "https://e/x", UpdatePolicy.sha256Hex(file), 3, "", "")
        assertTrue(UpdatePolicy.matches(file, published))
        assertFalse(UpdatePolicy.matches(file, published.copy(sizeBytes = 4)))
        assertFalse(UpdatePolicy.matches(file, published.copy(sha256 = "0".repeat(64))))
        assertFalse(UpdatePolicy.matches(File(dir, "missing"), published))
    }

    // ---- network (local server) ----

    @Test
    fun fetchesTheManifestAndFollowsARedirect() {
        server.routes["/latest"] = Triple(302, "/update.json", ByteArray(0))
        assertEquals(38, UpdatePolicy.parse(UpdateDownload.fetchText("$base/latest"))!!.versionCode)
    }

    @Test
    fun manifestFailuresAreNetworkErrors() {
        assertEquals(UpdateFailureKind.NETWORK, assertThrows(UpdateException::class.java) { UpdateDownload.fetchText("$base/gone") }.kind)
        server.stop()
        assertEquals(UpdateFailureKind.NETWORK, assertThrows(UpdateException::class.java) { UpdateDownload.fetchText("$base/update.json", timeoutMs = 500) }.kind)
    }

    @Test
    fun oversizedManifestIsRefused() {
        server.routes["/huge"] = Triple(200, null, ByteArray(UpdatePolicy.MAX_MANIFEST_BYTES + 10) { 'a'.code.toByte() })
        assertEquals(UpdateFailureKind.NETWORK, assertThrows(UpdateException::class.java) { UpdateDownload.fetchText("$base/huge") }.kind)
    }

    @Test
    fun manifestFallsBackOverHttpWhenPrimaryFailsOrReturnsNonJson() {
        server.routes["/html"] = Triple(200, null, "<html>blocked</html>".toByteArray())
        // Primary HTTP 404 -> falls back to /update.json over real HTTP.
        val from404 = UpdateChannel.fetchInfo("$base/gone", "$base/update.json", UpdateChannel.STABLE)
        assertEquals(38, from404?.versionCode)

        // Primary HTTP 200 HTML block page -> falls back to /update.json over real HTTP.
        val fromHtml = UpdateChannel.fetchInfo("$base/html", "$base/update.json", UpdateChannel.STABLE)
        assertEquals(38, fromHtml?.versionCode)

        // Primary works -> returns without failing.
        val fromPrimary = UpdateChannel.fetchInfo("$base/update.json", "$base/gone", UpdateChannel.STABLE)
        assertEquals(38, fromPrimary?.versionCode)
    }

    @Test
    fun downloadFollowsTheRedirectVerifiesAndReportsProgress() = runBlocking {
        val target = File(dir, "update/app.apk")
        var last = 0L
        UpdateDownload.downloadVerified(info("/apk"), target) { last = it }
        assertEquals(apk.size.toLong(), last)
        assertTrue(target.readBytes().contentEquals(apk))
        assertEquals(listOf("app.apk"), target.parentFile.list()!!.toList()) // no .part left
    }

    @Test
    fun anAlreadyVerifiedFileIsNotDownloadedAgain() = runBlocking {
        val target = File(dir, "app.apk").apply { writeBytes(apk) }
        server.stop() // would fail if it tried the network
        UpdateDownload.downloadVerified(info("/real"), target)
        assertTrue(UpdatePolicy.matches(target, info("/real")))
    }

    @Test
    fun wrongHashDeletesEverything() = runBlocking {
        val target = File(dir, "app.apk")
        val error = assertThrows(UpdateException::class.java) { runBlocking { UpdateDownload.downloadVerified(info("/flipped"), target) } }
        assertEquals(UpdateFailureKind.HASH, error.kind)
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.startsWith("app.apk") })
    }

    @Test
    fun wrongSizeDeletesEverything() = runBlocking {
        val target = File(dir, "app.apk")
        for (path in listOf("/short", "/long")) {
            val error = assertThrows(UpdateException::class.java) { runBlocking { UpdateDownload.downloadVerified(info(path), target) } }
            assertEquals(path, UpdateFailureKind.SIZE, error.kind)
            assertEquals(path, emptyList<String>(), dir.list()!!.filter { it.startsWith("app.apk") })
        }
    }

    @Test
    fun httpErrorAndDeadServerAreNetworkErrors() = runBlocking {
        val target = File(dir, "app.apk")
        assertEquals(UpdateFailureKind.NETWORK,
            assertThrows(UpdateException::class.java) { runBlocking { UpdateDownload.downloadVerified(info("/gone"), target) } }.kind)
        server.stop()
        assertEquals(UpdateFailureKind.NETWORK,
            assertThrows(UpdateException::class.java) { runBlocking { UpdateDownload.downloadVerified(info("/apk"), target, timeoutMs = 500) } }.kind)
        assertFalse(target.exists())
    }
}
