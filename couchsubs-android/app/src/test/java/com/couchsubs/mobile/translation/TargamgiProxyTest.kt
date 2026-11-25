// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.PackMatch
import com.couchsubs.mobile.core.TextTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TargamgiProxyTest {
    private val okReply = 200 to """{"candidates":[{"content":{"parts":[{"text":"[\"اهرب الآن!\"]"}]}}]}"""

    @Test
    fun proxyRequestUsesServerUrlAppTokenHeaderNoGoogleKeyAndSameGeminiBody() {
        val direct = AiRequest.build(
            AiProvider.GEMINI, "google-secret-key", TargamgiProxy.MODEL,
            listOf("Run now!"), emptyList(), target = "ar",
        )
        val proxied = AiRequest.build(
            AiProvider.GEMINI, "app-token-123", TargamgiProxy.MODEL,
            listOf("Run now!"), emptyList(), target = "ar",
            proxyUrl = TargamgiProxy.BASE_URL,
        )

        assertEquals(
            "https://bmbjlvbldyxkmxoinzdd.supabase.co/functions/v1/translate?model=${TargamgiProxy.MODEL_ID}",
            proxied.url,
        )
        assertEquals("app-token-123", proxied.headers["x-app-token"])
        assertEquals("application/json", proxied.headers["Content-Type"])
        assertNull(proxied.headers["x-goog-api-key"])
        assertFalse(proxied.url.contains("googleapis.com"))
        assertEquals(direct.body, proxied.body)
    }

    @Test
    fun proxyAvailabilityDependsOnNonBlankToken() {
        assertFalse(TargamgiProxy.isAvailable(""))
        assertFalse(TargamgiProxy.isAvailable("   "))
        assertTrue(TargamgiProxy.isAvailable("valid-token"))
    }

    @Test
    fun proxyUses15SecondTimeoutAndCleanPromptWhenSelected() {
        var seenUrl = ""
        var seenHeaders: Map<String, String> = emptyMap()
        var seenTimeout = 0
        var seenBody = ""
        val http = Http { _, url, headers, body, timeoutMs ->
            seenUrl = url
            seenHeaders = headers
            seenBody = body.orEmpty()
            seenTimeout = timeoutMs
            okReply
        }
        val translator = AiTranslator(
            AiProvider.GEMINI, "tok-xyz", TargamgiProxy.MODEL,
            fallback = { "مجاني: $it" }, http = http, target = "ar",
            proxyUrl = TargamgiProxy.BASE_URL,
            arabicPrompt = ArabicPrompt.CLEAN_FALLBACK,
        )

        assertEquals("اهرب الآن!", translator.translate("Run now!"))
        assertEquals(TargamgiProxy.url(), seenUrl)
        assertEquals("tok-xyz", seenHeaders["x-app-token"])
        assertNull(seenHeaders["x-goog-api-key"])
        assertEquals(15_000, seenTimeout)
        assertTrue(seenBody.contains(ArabicPrompt.CLEAN_FALLBACK))
    }

    @Test
    fun packComesFirstAndProxyFailuresFallBackToFreeEngine() {
        val failures: List<Pair<String, Http>> = listOf(
            "no-network" to Http { _, _, _, _, _ -> throw UnknownHostException("offline") },
            "timeout" to Http { _, _, _, _, _ -> throw SocketTimeoutException("read timed out") },
            "401" to Http { _, _, _, _, _ -> 401 to """{"error":"bad token"}""" },
            "429" to Http { _, _, _, _, _ -> 429 to """{"error":"busy"}""" },
            "503" to Http { _, _, _, _, _ -> 503 to """{"error":"keys unavailable"}""" },
        )

        for ((label, failingHttp) in failures) {
            var proxyHits = 0
            val countingHttp = Http { m, u, h, b, t ->
                proxyHits++
                failingHttp.send(m, u, h, b, t)
            }
            val free = object : TextTranslator {
                override val translationLanguage = "ar"
                override fun translate(text: String) = "مجاني: $text"
            }
            val proxy = AiTranslator(
                AiProvider.GEMINI, "tok", TargamgiProxy.MODEL,
                fallback = free, http = countingHttp, target = "ar",
                proxyUrl = TargamgiProxy.BASE_URL,
            )
            val packFirst = PackFirstTranslator(proxy) { text ->
                if (text == "Known.") PackMatch("من الحزمة") else null
            }

            assertEquals(label, "من الحزمة", packFirst.translate("Known."))
            assertEquals(label, 0, proxyHits)
            assertEquals(label, "مجاني: Unknown.", packFirst.translate("Unknown."))
            assertEquals(label, 1, proxyHits)
        }
    }
}
