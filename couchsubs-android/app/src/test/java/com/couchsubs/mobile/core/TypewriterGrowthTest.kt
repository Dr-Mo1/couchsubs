// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import com.couchsubs.mobile.service.LiveState
import com.couchsubs.mobile.service.LiveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 item 1: text that types itself letter by letter ("Time to re" then "Time to recover"). A longer reading takes the place
 * of the line on screen; a fragment is never a line of its own, never kept in the history, and a UI caption is not glued
 * onto a half line ("Strength" / "You need").
 */
class TypewriterGrowthTest {
    @Test fun extensionIsCanonicalAndNeedsRealGrowth() {
        assertTrue(TextMatch.extends("Time to recover", "Time to re"))
        // Case, spaces and punctuation are ignored on both sides.
        assertTrue(TextMatch.extends("TIME  TO, RECOVER!", "time to re..."))
        assertFalse(TextMatch.extends("Time to re", "Time to recover"))
        assertFalse(TextMatch.extends("Time to re.", "Time to re"))
        assertFalse(TextMatch.extends("Time to rest", "Time to recover"))
        // Two letters are not a line yet.
        assertFalse(TextMatch.extends("No way", "No"))
        // The stabilizer must not take a longer reading for the same subtitle just because the characters are close.
        assertFalse(TextMatch.isSameSubtitle("time to recover", "time to recove"))
        assertTrue(TextMatch.isRevision("Time to recover", "Time to re"))
    }

    /** The longer reading arrives while the fragment is still held: only the whole line is translated. */
    @Test fun growingLineIsTranslatedOnceWhole() {
        val script = PipelineScript()
        script.show("Time to re", 0, 100)
        script.frame("Time to recover.", 200)
        script.show("Time to recover.", 300, 800)
        assertEquals(listOf("Time to recover."), script.requested)
        assertEquals(listOf("ar:Time to recover."), script.displays.map { it.text })
    }

    /** The fragment was already shown: the whole line replaces it, and the history keeps one row, the whole line. */
    @Test fun shownFragmentIsReplacedAndNeverKeptInHistory() {
        val script = PipelineScript()
        script.show("Time to re", 0, 400)
        script.show("Time to recover.", 500, 900)
        assertEquals(listOf("Time to re", "Time to recover."), script.requested)
        assertEquals(listOf(false, true), script.displays.map { it.replaces })
        assertEquals(listOf(false, true), script.pairs.map { it.replaces })

        val history = SubtitleHistory()
        for (pair in script.pairs) history.add(pair.source, pair.translation, SubtitleHistory.ENGINE)
        assertEquals(listOf("Time to recover."), history.entries.map { it.source })

        // The list on the main screen mirrors the screen: the whole line takes the fragment's row.
        LiveState.update { LiveStatus() }
        for (pair in script.pairs) LiveState.shown(pair, null)
        assertEquals(listOf("Time to recover."), LiveState.status.value.recent.map { it.source })
        assertEquals(1, LiveState.status.value.shownLines)
    }

    /** A bare capitalised word above a new sentence is a caption, not part of the subtitle. */
    @Test fun captionAboveAHalfLineIsNotGluedOnto() {
        val area = CropRegion().box
        fun line(text: String, top: Double, bottom: Double, left: Double = 0.35, right: Double = 0.65) =
            TextLine(text, TextBox(left, top, right, bottom))
        for (typing in listOf("You need", "You n", "You need to recover.")) {
            val reading = Reading(listOf(line("Strength", 0.40, 0.55), line(typing, 0.58, 0.73, 0.4, 0.6)))
            assertEquals(typing, reading.subtitleBlock(area).text)
        }
        // A wrapped subtitle keeps both rows.
        val wrapped = Reading(listOf(line("I told you to meet me at the old", 0.40, 0.55, 0.2, 0.8), line("warehouse tonight.", 0.58, 0.73, 0.3, 0.7)))
        assertEquals("I told you to meet me at the old\nwarehouse tonight.", wrapped.subtitleBlock(area).text)
    }
}
