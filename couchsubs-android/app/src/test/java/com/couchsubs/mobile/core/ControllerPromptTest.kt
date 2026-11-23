// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.*

class ControllerPromptTest {
    private val hint = "Hold L2 or R2 and aim around until you locate a signal"
    @Test fun ocrButtonVariantsShareIdentity() {
        for (buttons in listOf("L2", "2", "△ ○ □ ✕", "Triangle Circle Square Cross Options Touchpad", "")) {
            val text = "Hold $buttons or and aim around until you locate a signal"
            assertTrue(text, ControllerPrompt.isPrompt(text))
            assertTrue(text, ControllerPrompt.same(hint, text))
            assertTrue(text, ControllerPrompt.same(hint, text.replace("locate", "locete")))
        }
    }
    @Test fun safeSpeechAndNumbersRemainDialogue() {
        assertTrue(ControllerPrompt.isPrompt("To aim, hold L2 and look around"))
        for (text in listOf("Lucia: Hold L2 and look around.", "Hold the gate", "Hold your fire until I return.", "Hold on!")) assertFalse(text, ControllerPrompt.isPrompt(text))
        assertFalse(ControllerPrompt.same("We need 2 cars.", "We need 3 cars."))
    }
    @Test fun divingHintNeverSupersedesPendingDialogue() {
        for (language in listOf("ar", "tr")) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val sent = CopyOnWriteArrayList<String>()
            var now = 0L
            val translator = object : TextTranslator {
                override fun translate(text: String): String { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return "ترجمة الكلام" }
            }
            val sender = object : SubtitleSender {
                override fun sendSubtitle(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) { sent.add(text) }
                override fun clear() {}
            }
            try {
                val pipeline = SubtitlePipeline(translator, sender, { now }, executor).apply {
                    translationLanguage = language; skipsTurkish = language == "tr"; packWait = 0; cleanWait = 0
                }
                val area = TextBox(0.0, 0.0, 1.0, 1.0)
                fun read(text: String) { pipeline.process(Reading(listOf(TextLine(text, TextBox(0.15, 0.6, 0.85, 0.67)))), area); now += 500 }
                repeat(2) { read("Didn't we pay a guide? I don't feel guided right now.") }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                for (text in listOf(hint, "Hold 2 or and aim around until you locate a signal", "Hold or and aim around until you locate a signal")) repeat(2) { read(text) }
                release.countDown(); executor.submit {}.get(5, TimeUnit.SECONDS)
                assertTrue(language + sent, sent.any { it.contains("ترجمة الكلام") })
                assertEquals(1, sent.size)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }
}
