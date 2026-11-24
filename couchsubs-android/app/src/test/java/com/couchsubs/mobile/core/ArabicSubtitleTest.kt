// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import org.junit.Assert.*
import org.junit.Test

class ArabicSubtitleTest {
    @Test fun latinLabelsAndWrappedDialogueAreSeparate() {
        assertEquals("أبو علي حسن", ArabicSubtitle.turns("أبو  علي  حسن: هنا.").single().name)
        val turns = ArabicSubtitle.turns("Doug: إنه هنا\nفي مكان ما.\nلوسيا: أين؟")
        assertEquals(listOf(ArabicSubtitle.Turn("دوغ", "إنه هنا\nفي مكان ما."), ArabicSubtitle.Turn("لوسيا", "أين؟")), turns)
        assertEquals("كينغفيشرز", ArabicSubtitle.turns("Kingfishers: لا أعرف من هو دوني.").single().name)
    }
    @Test fun timesUrlsAndHeadingsAreDialogue() {
        for (text in listOf("10:30", "https://example.com", "الهدف: عد إلى المنزل.", "Objective: عد إلى المنزل.", "ملاحظة: افتح الباب."))
            assertNull(text, ArabicSubtitle.turns(text).single().name)
    }
    @Test fun arbitraryNamesNeverLeaveLatinAndExistingArabicIsPreserved() {
        for (name in listOf("Someone Unknown", "O'Connor", "Unknown's", "Éric", "Vice City")) {
            val shown = ArabicSpeakerNames.display(name)
            assertTrue(shown.isNotBlank())
            assertFalse(shown, shown.any { it in 'A'..'Z' || it in 'a'..'z' })
        }
        assertEquals("أندريا", ArabicSpeakerNames.display("أندريا"))
        assertEquals("دوغ: هنا.", SpeakerPrefix.present("هنا.", "Doug: Here.", true, "ar"))
        assertEquals("هنا.", SpeakerPrefix.present("دوغ: هنا.", "Doug: Here.", false, "ar"))
    }
    @Test fun colorsAndIsolationAreStableAcrossAliases() {
        for ((name, color) in listOf("Jason" to "#4FC3F7", "Lucia" to "#FF8A80", "Brian" to "#81C784", "Doug" to "#FFD54F")) {
            assertEquals(color, ArabicSubtitle.color(name))
            assertEquals(color, ArabicSubtitle.color(ArabicSpeakerNames.display(name)))
        }
        assertEquals(ArabicSubtitle.color("Kingfishers"), ArabicSubtitle.color("كينغفيشرز"))
        assertEquals("\u2068Doug:\u2069", ArabicSubtitle.isolate("Doug:"))
    }
}
