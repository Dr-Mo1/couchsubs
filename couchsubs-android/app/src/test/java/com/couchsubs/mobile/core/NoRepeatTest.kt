// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A2 item 2 (tester report 13): the same line must not be shown twice while it stays on screen. Two readings the stabilizer
 * takes for different lines (OCR flicker, a reading that differs by more than its similarity limit) used to give the same
 * translation twice, which the phone stacks as "previous" and "current". One canonical-text check in the pipeline's single
 * display function now covers the TV, the phone, the history and the lists.
 */
class NoRepeatTest {
    private val answers = mapOf(
        "Come over here right now." to "تعال إلى هنا",
        "Get over here, quickly." to "تعال إلى هنا",
        "Where did you put the keys?" to "أين وضعت المفاتيح؟",
    )
    private fun script() = PipelineScript { answers.getValue(it) }

    @Test fun sameLineIsNotShownTwiceWhileItStaysOnScreen() {
        val script = script()
        script.show("Come over here right now.", 0, 300)
        // A flickering reading of the same line, different enough to be a new line: same translation.
        script.show("Get over here, quickly.", 400, 700)
        script.show("Come over here right now.", 800, 1100)
        assertEquals(listOf("تعال إلى هنا"), script.events)
        assertEquals(1, script.pairs.size)
    }

    @Test fun differentLineInBetweenAllowsTheSameLineAgain() {
        val script = script()
        script.show("Come over here right now.", 0, 300)
        script.show("Where did you put the keys?", 400, 700)
        script.show("Get over here, quickly.", 800, 1100)
        assertEquals(listOf("تعال إلى هنا", "أين وضعت المفاتيح؟", "تعال إلى هنا"), script.events)
    }

    @Test fun afterTheLineLeftTheScreenItMayComeBack() {
        val script = script()
        script.show("Come over here right now.", 0, 300)
        // Nothing on screen for a second: the stabilizer clears, then the same line is said again.
        script.show("", 400, 1500)
        script.show("Get over here, quickly.", 1600, 1900)
        assertEquals(listOf("تعال إلى هنا", "<clear>", "تعال إلى هنا"), script.events)
    }

    @Test fun historyKeepsOneRowForTheSameTranslationInAShortWindow() {
        val history = SubtitleHistory()
        history.add("Come over here right now.", "تعال إلى هنا", date = 1_000)
        history.add("Get over here, quickly.", "تعال إلى هنا", date = 3_000)
        assertEquals(1, history.entries.size)
        history.add("Where did you put the keys?", "أين وضعت المفاتيح؟", date = 4_000)
        // Different line in between, or outside the window: a new row.
        history.add("Get over here, quickly.", "تعال إلى هنا", date = 5_000)
        history.add("Come over here right now.", "تعال إلى هنا", date = 5_000L + SubtitlePipeline.DUPLICATE_WITHIN_MS + 1)
        assertEquals(4, history.entries.size)
    }
}
