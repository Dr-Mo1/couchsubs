// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicCleanStyleTest {
    @Test
    fun missingCleanFilesFallBackSafelyWithoutLeakingStreetData() {
        val assets = mapOf(
            ArabicStyleAssets.PROMPT_LIVE_ASSET to "STREET PROMPT",
            ArabicStyleAssets.GLOSSARY_LIVE_ASSET to "come on = يلا",
            ArabicStyleAssets.CORRECTIONS_LIVE_ASSET to "Run! = اجري يا سطى!",
        )

        val clean = ArabicStyleAssets.load(TranslationStyle.CLEAN) { assets[it] }
        assertEquals(TranslationStyle.CLEAN, clean.style)
        assertEquals(ArabicPrompt.CLEAN_FALLBACK, clean.prompt)
        assertTrue(clean.glossary.isEmpty())
        assertTrue(clean.corrections.isEmpty())
        assertEquals(ArabicStyleAssets.PACK_DIR_CLEAN, clean.packDirectory)
        assertEquals("packs-ar-clean", TranslationLanguage.packDirectory("ar", TranslationStyle.CLEAN))
        assertEquals("packs-ar", TranslationLanguage.packDirectory("ar", TranslationStyle.STREET))
    }

    @Test
    fun presentCleanFilesAreUsedInsteadOfFallbacks() {
        val assets = mapOf(
            ArabicStyleAssets.PROMPT_CLEAN_ASSET to "Custom clean MSA prompt\n",
            ArabicStyleAssets.GLOSSARY_CLEAN_ASSET to "# comment\ncome on = هيا\n",
            ArabicStyleAssets.CORRECTIONS_CLEAN_ASSET to "# fix\nRun! = اهرب!\\nالآن!\n",
        )

        val clean = ArabicStyleAssets.load(TranslationStyle.CLEAN) { assets[it] }
        assertEquals("Custom clean MSA prompt", clean.prompt)
        assertEquals(listOf("come on" to "هيا"), clean.glossary)
        assertEquals(mapOf("run" to "اهرب!\nالآن!"), clean.corrections)
    }

    @Test
    fun streetStyleLoadsLiveAssetsAndFallsBackToBuiltInPrompt() {
        val street = ArabicStyleAssets.load(TranslationStyle.STREET) { null }
        assertEquals(ArabicPrompt.TEXT, street.prompt)
        assertTrue(street.glossary.isEmpty())
        assertTrue(street.corrections.isEmpty())
        assertEquals(ArabicStyleAssets.PACK_DIR_LIVE, street.packDirectory)
    }

    @Test
    fun styleSettingParsingDefaultsToStreet() {
        assertEquals(TranslationStyle.STREET, TranslationStyle.of(null))
        assertEquals(TranslationStyle.STREET, TranslationStyle.of(""))
        assertEquals(TranslationStyle.STREET, TranslationStyle.of("unknown"))
        assertEquals(TranslationStyle.STREET, TranslationStyle.of("street"))
        assertEquals(TranslationStyle.CLEAN, TranslationStyle.of("clean"))
        assertEquals(TranslationStyle.CLEAN, TranslationStyle.of("CLEAN"))
    }

    @Test
    fun cleanAndStreetPromptsKeepSeparateCacheEntries() {
        val cache = TranslationCache()
        val bodies = mutableListOf<String>()
        val ok = 200 to JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", """["ترجمة"]"""))))),
        ).toString()
        val http = Http { _, _, _, body, _ -> bodies.add(body.orEmpty()); ok }

        val street = AiTranslator(AiProvider.GEMINI, "tok", TargamgiProxy.MODEL, null, http = http, cache = cache, target = "ar",
            proxyUrl = TargamgiProxy.BASE_URL, arabicPrompt = ArabicPrompt.TEXT)
        val clean = AiTranslator(AiProvider.GEMINI, "tok", TargamgiProxy.MODEL, null, http = http, cache = cache, target = "ar",
            proxyUrl = TargamgiProxy.BASE_URL, arabicPrompt = ArabicPrompt.CLEAN_FALLBACK)

        street.translate("Get out!")
        clean.translate("Get out!")

        assertEquals(2, bodies.size)
        assertTrue(bodies[0].contains("Egyptian"))
        assertTrue(bodies[1].contains(ArabicPrompt.CLEAN_FALLBACK))
    }

    @Test
    fun nonGtaGamesForceCleanPromptAndFilterProfanityEvenInStreetMode() {
        org.junit.Assert.assertTrue(TranslationStyle.allowsStreet(null))
        org.junit.Assert.assertTrue(TranslationStyle.allowsStreet(""))
        org.junit.Assert.assertTrue(TranslationStyle.allowsStreet("Grand Theft Auto VI"))
        org.junit.Assert.assertTrue(TranslationStyle.allowsStreet("GTA 6"))
        org.junit.Assert.assertTrue(TranslationStyle.allowsStreet("Grand Theft Auto V"))
        org.junit.Assert.assertFalse(TranslationStyle.allowsStreet("God of War Ragnarök"))
        org.junit.Assert.assertFalse(TranslationStyle.allowsStreet("Resident Evil Requiem"))
        org.junit.Assert.assertFalse(TranslationStyle.allowsStreet("Tomb Raider"))
        assertEquals(TranslationStyle.CLEAN, TranslationStyle.effective(TranslationStyle.STREET, "God of War Ragnarök"))
        assertEquals(TranslationStyle.STREET, TranslationStyle.effective(TranslationStyle.STREET, "Grand Theft Auto VI"))

        val bodies = mutableListOf<String>()
        val ok = 200 to JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", """["انزل يا ابن المتناكة!"]"""))))),
        ).toString()
        val http = Http { _, _, _, body, _ -> bodies.add(body.orEmpty()); ok }

        var activeGame: String? = "God of War Ragnarök"
        val translator = AiTranslator(
            AiProvider.GEMINI, "tok", TargamgiProxy.MODEL, null,
            http = http, cache = TranslationCache(), playedGame = { activeGame },
            target = "ar", slang = listOf("get out" to "انزل يا سطى"),
            proxyUrl = TargamgiProxy.BASE_URL, arabicPrompt = ArabicPrompt.TEXT,
            corrections = mapOf("get out" to "انزل يا ابن المتناكة!"),
        )

        val nonGtaResult = translator.translate("Get out!")
        assertEquals(1, bodies.size)
        assertTrue(bodies[0].contains(ArabicPrompt.CLEAN_FALLBACK))
        org.junit.Assert.assertFalse(bodies[0].contains("انزل يا سطى"))
        org.junit.Assert.assertFalse(nonGtaResult.contains("المتناكة"))
    }
}
