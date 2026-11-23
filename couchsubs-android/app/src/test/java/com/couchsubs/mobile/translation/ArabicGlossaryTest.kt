// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import org.junit.Assert.*
import org.junit.Test

class ArabicGlossaryTest {
    @Test fun commentsInvalidRowsAndDuplicateKeys() {
        assertEquals(listOf("come on" to "يلا", "don't" to "متعملش"),
            ArabicGlossary.parse("# note\n\nCome On = يلا\ncome-on=duplicate\nbad\n=empty\nnone=\nDON’T = متعملش"))
    }
    @Test fun wholeWordsApostrophesAndLongestPhrase() {
        val glossary = ArabicGlossary.parse("cop=شرطي\ncops=شرطة\ncome on=يلا\ncome on down=تعال\ndon't=متعملش")
        assertEquals(listOf("cops" to "شرطة", "come on down" to "تعال", "don't" to "متعملش"),
            ArabicGlossary.inLines(glossary, listOf("COPS! Come on down; don’t stop.")))
        assertTrue(ArabicGlossary.inLines(glossary, listOf("helicopter" )).isEmpty())
    }
    @Test fun atMostTwelveAndOnlyCurrentLines() {
        val glossary = (1..20).map { "word$it" to "تعال$it" }
        assertEquals(12, ArabicGlossary.inLines(glossary, listOf(glossary.joinToString(" ") { it.first })).size)
        assertTrue(ArabicGlossary.inLines(glossary, listOf("unrelated")).isEmpty())
    }
}
