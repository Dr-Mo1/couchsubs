// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class InstructionDisplayTest {
    @Test fun expiresAtFourSecondsAndRemembersAfterRemoval() {
        var now = 0L; var hidden = 0; var shown = 0
        val display = InstructionDisplay({ now }) { hidden++ }
        val hint = "Hold L2 or R2 and aim around until you locate a signal"
        assertTrue(display.publish(hint, display.version()) { shown++ })
        now = 3999; display.expire(); assertEquals(0, hidden)
        now = 4000; display.expire(); assertEquals(1, hidden)
        display.removed()
        assertFalse(display.publish("Hold 2 or and aim around until you locate a signal", display.version()) { shown++ })
        assertEquals(1, shown)
        display.reset()
    }
    @Test fun dialogueInvalidatesInflightHintAndEvictsOnlyAfterTwentyIdentities() {
        val display = InstructionDisplay({ 0L }) {}
        val old = display.version(); display.dialogueArrived()
        assertFalse(display.publish("Hold L2", old) { fail() })
        repeat(21) { index -> assertTrue(display.publish("Press L2 for objective ${index + 100}", display.version()) {}) }
        assertFalse(display.contains("Press L2 for objective 100"))
        assertTrue(display.contains("Press L2 for objective 120"))
        display.reset()
    }
    @Test fun realTimerExpiresWithoutNewFrames() {
        val expired = CountDownLatch(1)
        val display = InstructionDisplay(System::currentTimeMillis) { expired.countDown() }
        try {
            assertTrue(display.publish("Press L2", display.version()) {})
            assertTrue(expired.await(6, TimeUnit.SECONDS))
        } finally { display.reset() }
    }
}
