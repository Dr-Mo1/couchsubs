// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.TranslationDialogue
import org.junit.Assert.*
import org.junit.Test

class ArabicDialogueTest {
    @Test fun speakerListenerGenderAndContinuation() {
        val context = listOf(AiRequest.ContextLine("We need to", "نحتاج أن", "Lucia"))
        val message = AiRequest.userMessage(listOf("and go, Jason!"), context, target = "ar", speaker = "Lucia")
        assertTrue(message.contains("Speaker is a woman."))
        assertTrue(message.contains("Listener (Jason) is a man."))
        assertTrue(message.contains("continues the previous one"))
        assertFalse(AiRequest.userMessage(listOf("and go"), context, target = "ar", speaker = "Jason").contains("continues the previous one"))
        assertFalse(AiRequest.userMessage(listOf("and go"), context, target = "tr", speaker = "Lucia").contains("Speaker:"))
    }
    @Test fun scopeIsRestoredOnErrorAndNestedFallback() {
        assertNull(TranslationDialogue.currentSpeaker)
        TranslationDialogue.withSpeaker("Lucia") {
            try { TranslationDialogue.withSpeaker("Jason") { throw IllegalStateException() } } catch (_: IllegalStateException) {}
            assertEquals("Lucia", TranslationDialogue.currentSpeaker)
        }
        assertNull(TranslationDialogue.currentSpeaker)
    }
}
