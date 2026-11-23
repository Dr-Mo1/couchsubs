// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.TranslationDialogue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** All provider requests use fake HTTP; no provider or socket is contacted. */
class TargamgiRequestTest {
    private fun response(text: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", JSONArray().put(text).toString())))).toString()

    @Test fun everyProviderReceivesTheSelectedTargetAndGlossary() {
        for (provider in AiProvider.entries) {
            val request = AiRequest.build(provider, "fake", AiModel("fake", "fake"), listOf("Come on."), emptyList(),
                target = "ar", slang = listOf("come on" to "يلا"), speaker = "Lucia",
                server = if (provider == AiProvider.LOCAL) LocalAiServer("http://127.0.0.1:1234/v1", "fake") else null)
            assertTrue(request.body.contains("Egyptian"))
            assertTrue(request.body.contains("Speaker is a woman."))
            assertTrue(request.body.contains("يلا"))
            assertFalse(request.body.contains("into Turkish"))
        }
        assertEquals("AR", JSONObject(DeepLTranslator.body(listOf("Hello."), null, target = "ar")).getString("target_lang"))
        assertEquals("TR", JSONObject(DeepLTranslator.body(listOf("Hello."), null)).getString("target_lang"))
    }

    @Test fun keyValidationTestsTheSelectedLanguage() {
        var body = ""
        val result = KeyCheck.aiSample(AiProvider.OPENAI, "fake", AiModel("fake", "fake"),
            http = Http { _, _, _, json, _ -> body = json!!; 200 to response("تعال.") }, target = "ar")
        assertEquals(OnlineKeyState.VALID, result.first)
        assertEquals("تعال.", result.second)
        assertTrue(body.contains("Egyptian"))
    }

    @Test fun subtitleAndScreenPathsFilterTheGlossaryAndKeepTenArabicContextLines() {
        val requests = mutableListOf<String>()
        val glossary = ArabicGlossary.parse("come on = يلا\nunused phrase = غير مستخدم")
        val engine = AiTranslator(AiProvider.OPENAI, "fake", AiModel("fake", "fake"), null,
            http = Http { _, _, _, body, _ -> requests.add(body!!); 200 to response("ترجمة.") }, target = "ar", slang = glossary)
        TranslationDialogue.withSpeaker("Lucia") {
            repeat(12) { engine.translate("Unique sentence number $it.") }
            engine.translate("Come on.")
        }
        val last = JSONObject(requests.last()).getJSONArray("messages").getJSONObject(1).getString("content")
        assertEquals(10, Regex("English:").findAll(last).count())
        assertTrue(last.contains("come on")); assertFalse(last.contains("unused phrase"))
        assertTrue(last.contains("Speaker: Lucia"))
        engine.translateAll(listOf("Come on now."))
        val screen = requests.last()
        assertTrue(screen.contains("يلا")); assertFalse(screen.contains("غير مستخدم"))
    }

    @Test fun sharedCacheSeparatesLanguageAndSpeakerAndTurkishKeepsThreeContextLines() {
        val cache = TranslationCache()
        val requests = mutableListOf<String>()
        fun engine(target: String) = AiTranslator(AiProvider.OPENAI, "fake", AiModel("fake", "fake"), null,
            cache = cache, http = Http { _, _, _, body, _ -> requests.add(body!!); 200 to response("ترجمة.") }, target = target)
        val ar = engine("ar")
        TranslationDialogue.withSpeaker("Lucia") { ar.translate("The same line."); ar.translate("The same line.") }
        assertEquals(1, requests.size)
        TranslationDialogue.withSpeaker("Jason") { ar.translate("The same line.") }
        assertEquals(2, requests.size)
        val tr = engine("tr"); tr.translate("The same line.")
        assertEquals(3, requests.size)
        repeat(5) { tr.translate("Other sentence $it.") }
        val user = JSONObject(requests.last()).getJSONArray("messages").getJSONObject(1).getString("content")
        assertEquals(3, Regex("English:").findAll(user).count())
    }
}
