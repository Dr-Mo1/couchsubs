// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.*
import org.junit.Assert.*
import org.junit.Test

class ArabicEngineOrderTest {
    private val pack = PackMatch("باقة")
    @Test fun packFirstThenAiDeepLAndRawArabicMachine() {
        val calls = mutableListOf<String>()
        val machine = TargetLineTranslator("ar", TranslationCache()) { calls.add("mlkit-ar"); "ترجمة" }
        val http = Http { _, _, _, _, _ -> calls.add("deepl-ar"); 503 to "" }
        val deepl = DeepLTranslator("fake", machine, http = http, target = "ar")
        val ai = AiTranslator(AiProvider.GEMINI, "fake", AiModel("fake", "fake"), deepl,
            http = Http { _, _, _, _, _ -> calls.add("ai-ar"); 503 to "" }, target = "ar")
        val translator = PackFirstTranslator(ai) { if (it == "Known.") pack else null }
        assertEquals("باقة", translator.translate("Known."))
        assertTrue(calls.isEmpty())
        assertEquals("ترجمة", translator.translate("Unknown line."))
        assertEquals(listOf("ai-ar", "deepl-ar", "mlkit-ar"), calls)
        assertEquals("ar", translator.translationLanguage)
    }
    @Test fun packBatchOnlySendsMissesToEngine() {
        val calls = mutableListOf<String>()
        val engine = object : TextTranslator {
            override val translationLanguage = "ar"
            override fun translate(text: String): String { calls.add(text); return "ترجمة" }
        }
        val translator = PackFirstTranslator(engine) { if (it == "Known.") pack else null }
        assertEquals(listOf("باقة", "ترجمة"), translator.translateAll(listOf("Known.", "Unknown.")))
        assertEquals(listOf("Unknown."), calls)
    }
}
