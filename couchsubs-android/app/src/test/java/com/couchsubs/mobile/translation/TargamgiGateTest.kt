// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.TextTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TargamgiGateTest {
    private val okReply = 200 to """{"candidates":[{"content":{"parts":[{"text":"[\"اهرب الآن!\"]"}]}}]}"""

    private val free = object : TextTranslator {
        override val translationLanguage = "ar"
        override fun translate(text: String) = "مجاني: $text"
    }

    private fun session(
        token: () -> String? = { "tok" }, refresh: () -> String? = { null }, onRefusal: (GateException) -> Unit = {},
    ) = TargamgiGate.Session("dev-12345", token, refresh, onRefusal)

    private fun translator(http: Http, session: TargamgiGate.Session, clock: () -> Long = { 0L }) = AiTranslator(
        AiProvider.GEMINI, "", TargamgiProxy.MODEL, free, http = http, target = "ar", proxyUrl = TargamgiGate.BASE_URL,
        gate = session, clock = clock,
    )

    // ---- the request ----

    @Test
    fun gateRequestHasTheFourHeadersTheGateUrlAndTheSameBodyAsTheProxy() {
        val proxied = AiRequest.build(AiProvider.GEMINI, "app-token", TargamgiProxy.MODEL, listOf("Run now!"), emptyList(),
            target = "ar", proxyUrl = TargamgiProxy.BASE_URL)
        val gated = AiRequest.build(AiProvider.GEMINI, "", TargamgiProxy.MODEL, listOf("Run now!"), emptyList(),
            target = "ar", proxyUrl = TargamgiGate.BASE_URL, gate = GateHeaders("access-1", "dev-12345"))

        assertEquals("https://bmbjlvbldyxkmxoinzdd.supabase.co/functions/v1/translate-gate?model=${TargamgiProxy.MODEL_ID}", gated.url)
        assertEquals(
            mapOf(
                "Authorization" to "Bearer access-1", "apikey" to TargamgiGate.PUBLISHABLE_KEY,
                "x-device-id" to "dev-12345", "Content-Type" to "application/json",
            ),
            gated.headers,
        )
        assertNull(gated.headers["x-app-token"])
        assertEquals(proxied.body, gated.body)
    }

    @Test
    fun gateIsAlwaysAvailableBecauseTheAccountProvidesTheCredentials() {
        assertTrue(TargamgiGate.isAvailable())
    }

    // ---- refresh once, retry once ----

    @Test
    fun a401RefreshesTheTokenOnceAndRetriesOnce() {
        val seen = mutableListOf<String?>()
        var refreshes = 0
        val http = Http { _, _, headers, _, _ ->
            seen += headers["Authorization"]
            if (seen.size == 1) 401 to """{"error":"unauthorized"}""" else okReply
        }
        val translator = translator(http, session(token = { "old" }, refresh = { refreshes++; "new" }))

        assertEquals("اهرب الآن!", translator.translate("Run now!"))
        assertEquals(listOf<String?>("Bearer old", "Bearer new"), seen)
        assertEquals(1, refreshes)
    }

    @Test
    fun aSecond401IsSurfacedAsAnAccountProblemNotRetriedAgain() {
        var calls = 0
        val refused = mutableListOf<GateException>()
        val http = Http { _, _, _, _, _ -> calls++; 401 to """{"error":"unauthorized"}""" }
        val translator = translator(http, session(refresh = { "new" }, onRefusal = { refused += it }))

        val error = assertThrows(GateException::class.java) { translator.translate("Run now!") }

        assertEquals(GateError.ACCOUNT_PROBLEM, error.error)
        assertEquals(2, calls)
        assertEquals(listOf(GateError.ACCOUNT_PROBLEM), refused.map { it.error })
    }

    // ---- 403 / 429 / 503 are typed errors, never a quiet fall back to the free engine ----

    @Test
    fun refusalsComeOutTypedAndTheFreeEngineIsNotUsed() {
        val cases = listOf(
            Triple(403, """{"error":"trial_expired"}""", GateError.ACCESS_ENDED),
            Triple(403, """{"error":"plan_expired"}""", GateError.ACCESS_ENDED),
            Triple(403, """{"error":"trial_used_on_device"}""", GateError.ACCESS_ENDED),
            Triple(403, """{"error":"account_not_found"}""", GateError.ACCOUNT_PROBLEM),
            Triple(429, """{"error":"cap_reached","period":"daily"}""", GateError.CAP_REACHED),
            Triple(503, """{"error":"maintenance","message":"Back in an hour"}""", GateError.MAINTENANCE),
        )
        for ((status, body, expected) in cases) {
            var refused: GateException? = null
            val translator = translator(Http { _, _, _, _, _ -> status to body }, session(onRefusal = { refused = it }))

            val error = assertThrows(body, GateException::class.java) { translator.translate("Run now!") }

            assertEquals(body, expected, error.error)
            assertEquals(body, expected, refused?.error)
        }
    }

    @Test
    fun maintenanceKeepsTheServersMessage() {
        val error = GateException.from(503, """{"error":"maintenance","message":"Back in an hour"}""")
        assertEquals("Back in an hour", error?.serverMessage)
        assertEquals("maintenance", error?.code)
        assertNull(GateException.from(503, "<html>down</html>")?.serverMessage)
        assertEquals(GateError.MAINTENANCE, GateException.from(503, "<html>down</html>")?.error)
    }

    @Test
    fun otherStatusesKeepTheOrdinaryFallBackToTheFreeEngine() {
        for (status in listOf(200, 400, 404, 500, 502)) assertNull(status.toString(), GateException.from(status, """{"error":"upstream_failed"}"""))
        val upstream = translator(Http { _, _, _, _, _ -> 502 to """{"error":"upstream_failed"}""" }, session())
        assertEquals("مجاني: Run now!", upstream.translate("Run now!"))
        val offline = translator(Http { _, _, _, _, _ -> throw java.net.UnknownHostException("offline") }, session())
        assertEquals("مجاني: Run now!", offline.translate("Run now!"))
        // No account session at all (no connection for the sign-in): also the ordinary fall back.
        val noSession = translator(Http { _, _, _, _, _ -> throw AssertionError("no call expected") }, session(token = { null }))
        assertEquals("مجاني: Run now!", noSession.translate("Run now!"))
    }

    @Test
    fun theScreenTranslationIsRefusedToo() {
        val translator = translator(Http { _, _, _, _, _ -> 403 to """{"error":"plan_expired"}""" }, session())
        val error = assertThrows(GateException::class.java) { translator.translateAll(listOf("Run now!", "Stop!")) }
        assertEquals(GateError.ACCESS_ENDED, error.error)
    }

    @Test
    fun aRefusalIsReplayedForTenSecondsInsteadOfAskingTheServerOnEveryRetry() {
        var calls = 0
        var now = 0L
        val http = Http { _, _, _, _, _ -> calls++; 403 to """{"error":"trial_expired"}""" }
        val translator = translator(http, session(), clock = { now })

        assertThrows(GateException::class.java) { translator.translate("Run now!") }
        now = 9_999
        assertThrows(GateException::class.java) { translator.translate("Stop!") }
        assertEquals(1, calls)
        now = TargamgiGate.REFUSAL_REPLAY_MS
        assertThrows(GateException::class.java) { translator.translate("Stop!") }
        assertEquals(2, calls)
    }

    // ---- the cache identity follows the style (1.0.48 item 9) ----

    @Test
    fun theSameLineInStreetAndCleanStyleUsesTwoDifferentCaches() {
        val directory = Files.createTempDirectory("style-cache").toFile()
        try {
            val base = AiTranslator.proxyEngineId(TargamgiProxy.MODEL)
            val street = TranslationStyle.cacheEngineId(base, "ar", TranslationStyle.STREET)
            val clean = TranslationStyle.cacheEngineId(base, "ar", TranslationStyle.CLEAN)
            assertEquals("targamgi:gemini-3.5-flash-lite", street)
            assertEquals("targamgi:gemini-3.5-flash-lite:clean", clean)
            assertNotEquals(TranslationCache.file(directory, street, "en", "ar"), TranslationCache.file(directory, clean, "en", "ar"))

            val streetCache = TranslationCache.shared(directory, street, target = "ar")
            val cleanCache = TranslationCache.shared(directory, clean, target = "ar")
            streetCache.store("Get out of here!", "street wording")
            assertEquals("street wording", streetCache.get("Get out of here!"))
            assertNull(cleanCache.get("Get out of here!"))
            cleanCache.store("Get out of here!", "clean wording")
            assertEquals("street wording", streetCache.get("Get out of here!"))
            assertEquals("clean wording", cleanCache.get("Get out of here!"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun streetStyleAndOtherEnginesKeepTheirExistingCacheId() {
        val directory = Files.createTempDirectory("style-cache-old").toFile()
        try {
            val base = AiTranslator.engineId(AiProvider.GEMINI, TargamgiProxy.MODEL)
            assertEquals(base, TranslationStyle.cacheEngineId(base, "ar", TranslationStyle.STREET))
            // Not Arabic: the style does not matter.
            assertEquals(base, TranslationStyle.cacheEngineId(base, "tr", TranslationStyle.CLEAN))
            // Nobody loses the cache file they already have.
            assertEquals(
                TranslationCache.file(directory, base, "en", "ar"),
                TranslationCache.file(directory, TranslationStyle.cacheEngineId(base, "ar", TranslationStyle.STREET), "en", "ar"),
            )
            assertFalse(TranslationStyle.cacheEngineId(base, "ar", TranslationStyle.STREET).endsWith(":clean"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
