// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.TextTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArabicProfanityFilterTest {
    @Test
    fun replacesEgyptianAndMsaSwearWordsAndPhrases() {
        assertEquals("تبًا، لقد رأونا!", ArabicProfanityFilter.clean("أحيه، لقد رأونا!"))
        assertEquals("تبًا!", ArabicProfanityFilter.clean("أحا!"))
        assertEquals("اخرج من السيارة أيها الوغد!", ArabicProfanityFilter.clean("اخرج من السيارة يا ابن الشرموطة!"))
        assertEquals("توقف أيها الوغد!", ArabicProfanityFilter.clean("توقف يا ابن المتناكة!"))
        assertEquals("ابتعد يا وغد.", ArabicProfanityFilter.clean("ابتعد يا معرص."))
        assertEquals("هي وغدة.", ArabicProfanityFilter.clean("هي عاهرة."))
        assertEquals("هذا تبًا!", ArabicProfanityFilter.clean("هذا خراء!"))
        assertEquals("يا جبان!", ArabicProfanityFilter.clean("يا خول!"))
    }

    @Test
    fun handlesDiacriticsAndProcliticsAndDropsExplicitWordsCleanly() {
        assertEquals("تبًا!", ArabicProfanityFilter.clean("أَحَّا!"))
        assertEquals("الوغدة هناك.", ArabicProfanityFilter.clean("الشرموطة هناك."))
        assertEquals("توقف الآن!", ArabicProfanityFilter.clean("توقف نيك الآن!"))
        assertEquals("توقف!", ArabicProfanityFilter.clean("توقف كس!"))
        val dropped = ArabicProfanityFilter.clean("انظر إلى عضو ذكري هنا.")
        assertFalse(dropped.contains("عضو ذكري"))
        assertFalse(dropped.contains("  "))
    }

    @Test
    fun doesNotFalsePositiveOnCleanArabicWords() {
        val cleanLines = listOf(
            "ماذا تفعل هنا يا جيسون؟",
            "انكسر الزجاج وأصبح مكسورًا.",
            "الزبون اشترى الزبدة من المتجر.",
            "زرت معرض السيارات أمس.",
            "علّق الجندي على التقرير في الوقت المحدد.",
            "لدي كلب صغير في المنزل.",
            "اصطدمت مؤخرة السيارة بالرصيف.",
        )
        for (line in cleanLines) {
            assertEquals(line, ArabicProfanityFilter.clean(line))
        }
    }

    @Test
    fun preservesSpeakerLabelsAndMultilineSubtitles() {
        val input = "LUCIA: أحا!\nاخرج يا معرص."
        val cleaned = ArabicProfanityFilter.clean(input)
        assertEquals("LUCIA: تبًا!\nاخرج يا وغد.", cleaned)
    }

    @Test
    fun cleanArabicTranslatorFiltersBothSingleAndBatchCalls() {
        val rawFree = object : TextTranslator {
            override val translationLanguage = "ar"
            override fun translate(text: String) = if (text == "Damn!") "أحيه!" else "اخرج يا ابن الكلب."
        }
        val filtered = CleanArabicTranslator(rawFree)
        assertEquals("ar", filtered.translationLanguage)
        assertEquals("تبًا!", filtered.translate("Damn!"))
        assertEquals(listOf("تبًا!", "اخرج أيها الوغد."), filtered.translateAll(listOf("Damn!", "Get out!")))
        // Unwrapped FREE translator stays untouched in street mode.
        assertTrue(rawFree.translate("Damn!").contains("أحيه"))
    }
}
