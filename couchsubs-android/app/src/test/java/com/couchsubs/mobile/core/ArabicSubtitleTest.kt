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
    @Test fun isolationIsStable() {
        assertEquals("\u2068Doug:\u2069", ArabicSubtitle.isolate("Doug:"))
    }
    /** A2 item 3 (tester report 12): the name is its own line above the dialogue, one amber for every character. */
    @Test fun speakerNameIsItsOwnAmberLineAboveTheDialogue() {
        assertEquals("#FFB627", ArabicSubtitle.SPEAKER_COLOR)
        // An Arabic label, an Egyptian label and an English label (shown in Arabic letters) all work the same way.
        for ((text, name) in listOf("كال: تحرك الآن." to "كال:", "ظابط: قف مكانك." to "ظابط:", "Cal: Move now." to "كال:")) {
            val rows = ArabicSubtitle.rows(text)
            assertEquals(text, 2, rows.size)
            assertEquals(ArabicSubtitle.Row(name, speaker = true), rows[0])
            assertEquals(false, rows[1].speaker)
            assertEquals(false, rows[1].centered)
        }
        // Two speakers: each name on its own line above its own dialogue; a line without a speaker stays centred, no name row.
        assertEquals(listOf("دوغ:", "هنا.", "لوسيا:", "أين؟"), ArabicSubtitle.rows("Doug: هنا.\nلوسيا: أين؟").map { it.text })
        assertEquals(listOf(ArabicSubtitle.Row("لا أحد هنا.", speaker = false, centered = true)), ArabicSubtitle.rows("لا أحد هنا."))
    }
}
