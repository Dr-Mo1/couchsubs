// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

class TargamgiLanguageTest {
    @Test fun languageIsSeparateFromOutputAndDefaultsToArabic() {
        assertEquals("ar", TranslationLanguage.DEFAULT)
        assertEquals("ar", TranslationLanguage.code(" AR "))
        assertEquals("Arabic", TranslationLanguage.name("ar"))
        assertEquals("French", TranslationLanguage.name("fr"))
        assertTrue(TranslationLanguage.supportsLocal("ar"))
        assertTrue(TranslationLanguage.supportsLocal("tr"))
        assertFalse(TranslationLanguage.supportsLocal("fr"))
        assertEquals("packs-ar", TranslationLanguage.packDirectory("ar"))
        assertEquals("packs", TranslationLanguage.packDirectory("tr"))
        assertNull(TranslationLanguage.packDirectory("fr"))
        assertEquals(10, TranslationLanguage.contextLines("ar"))
        assertEquals(3, TranslationLanguage.contextLines("tr"))
        assertEquals(AiRequest.SYSTEM_PROMPT, AiRequest.systemPrompt(null, "tr"))
        assertEquals(ArabicPrompt.TEXT, AiRequest.systemPrompt(null, "ar"))
        for (language in listOf("en", "fr", "de", "es")) {
            val prompt = AiRequest.systemPrompt(null, language)
            assertTrue(prompt.contains(TranslationLanguage.name(language)))
            assertFalse(prompt.contains("Turkish"))
            assertFalse(prompt.contains("Egyptian"))
        }
    }
    @Test fun savedTurkishTargetBecomesArabic() {
        assertEquals("ar", TranslationLanguage.stored("tr"))
        assertEquals("ar", TranslationLanguage.stored(null))
        assertEquals("fr", TranslationLanguage.stored("fr"))
        assertEquals("ar", TranslationLanguage.stored("ar"))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidLanguageRejected() { TranslationLanguage.code("../tr") }
    @Test fun rawArabicFallbackNeverRunsTurkishPostEditAndSeparatesSpeakers() {
        val cache = TranslationCache()
        var calls = 0
        val translator = TargetLineTranslator("ar", cache) { calls++; "حاضر." }
        TranslationDialogue.withSpeaker("Lucia") { assertEquals("حاضر.", translator.translate("Okay.")); translator.translate("Okay.") }
        assertEquals(1, calls)
        TranslationDialogue.withSpeaker("Jason") { translator.translate("Okay.") }
        assertEquals(2, calls)
        assertNull(TranslationDialogue.currentSpeaker)
        val tr = TargetLineTranslator("tr", cache) { "Tamam." }
        assertEquals("Tamam.", tr.translate("Okay."))
    }
    @Test fun promptFingerprintIncludesChangesAfterFirstThousandCharacters() {
        assertNotEquals(TranslationLanguage.fingerprint(ArabicPrompt.TEXT), TranslationLanguage.fingerprint(ArabicPrompt.TEXT + "changed"))
    }
    @Test fun realArabicPackFindsFiveKnownEnglishLines() {
        val file = File(System.getProperty("targamgi.dataDir", "D:/Games/couchsubs-ar/build/android-data"), "gta-6-ar.json.gz")
        assumeTrue("Local Arabic pack is absent", file.isFile)
        val library = GamePackLibrary({ listOf(file.name to { file.readBytes() }) }, null)
        for (english in listOf("Thank you.", "Come on.", "Get in the car.", "Let's go.", "Come here.")) {
            assertNotNull(PackKey.key(english))
            val hit = library.find(english)
            assertNotNull(english, hit)
            assertTrue(english, hit!!.any { it in '\u0600'..'\u06ff' })
        }
    }
}
