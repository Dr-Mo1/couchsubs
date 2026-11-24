// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import org.junit.Assert.*
import org.junit.Test

/** Port of Windows PlayerCharacterInferenceTests. */
class PlayerCharacterInferenceTest {
    @Test fun seventyPercentJasonIsEnoughAndWindowMovesToLucia() {
        val inference = PlayerCharacterInference { 0L }
        repeat(7) { inference.observe("JASON") }
        repeat(3) { inference.observe("Lucia") }
        assertEquals("Jason", inference.resolve())
        repeat(4) { inference.observe("LUCIA") }
        assertEquals("Lucia", inference.resolve())
    }

    @Test fun alternatingProtagonistsAndNoLabelsAreUnknown() {
        val inference = PlayerCharacterInference { 0L }
        assertNull(inference.resolve())
        inference.observe("Cal")
        assertNull(inference.resolve())
        repeat(10) { inference.observe(if (it % 2 == 0) "Jason" else "Lucia") }
        assertNull(inference.resolve())
    }

    @Test fun labelsExpireAfterFifteenMinutes() {
        var time = 0L
        val inference = PlayerCharacterInference { time }
        repeat(3) { inference.observe("Jason") }
        time = PlayerCharacterInference.WINDOW_MS
        assertEquals("Jason", inference.resolve())
        time++
        assertNull(inference.resolve())
        repeat(3) { inference.observe("Lucia") }
        assertEquals("Lucia", inference.resolve())
    }

    @Test fun oneOrTwoStrayLabelsStayUnknown() {
        val inference = PlayerCharacterInference { 0L }
        inference.observe("Lucia")
        assertNull(inference.resolve())
        inference.observe("Lucia")
        assertNull(inference.resolve())
        inference.observe("Lucia")
        assertEquals("Lucia", inference.resolve())
    }

    @Test fun manualChoiceWinsWithoutDiscardingInference() {
        val inference = PlayerCharacterInference { 0L }
        repeat(3) { inference.observe("Jason") }
        assertEquals("Lucia", inference.resolve("lucia"))
        assertEquals("Jason", inference.resolve("auto"))
        assertEquals("Jason", inference.resolve("invalid"))
        assertEquals("Jason", PlayerCharacterInference { 0L }.resolve("jason"))
    }

    @Test fun normalizationOnlyAcceptsTheTwoProtagonists() {
        assertEquals("Jason", PlayerCharacterInference.normalize(" JASON "))
        assertEquals("Lucia", PlayerCharacterInference.normalize("lucia"))
        for (label in listOf(null, "", "auto", "Cal", "STRANGER")) assertNull(PlayerCharacterInference.normalize(label))
    }
}
