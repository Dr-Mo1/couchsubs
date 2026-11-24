// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Player metadata reaches real provider request builders through fake HTTP only. */
class PlayerCharacterTranslationTest {
    private fun response(text: String) = JSONObject().put("choices", JSONArray().put(
        JSONObject().put("message", JSONObject().put("content", JSONArray().put(text).toString())))).toString()
    private fun user(body: String) = JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")

    @Test fun playerCharacterMessageIsArabicOnly() {
        for ((target, player, expected) in listOf(
            Triple("ar", "jason", "Player character: Jason (a man)"),
            Triple("ar", "lucia", "Player character: Lucia (a woman)"),
            Triple("ar", null, null), Triple("ar", "invalid", null), Triple("tr", "jason", null),
        )) {
            val message = AiRequest.userMessage(listOf("Get out!"), emptyList(), target = target, playerCharacter = player)
            if (expected == null) assertFalse(message.contains("Player character:")) else assertTrue(message.contains(expected))
        }
    }

    @Test fun everyProviderReceivesThePlayerCharacter() {
        for (provider in AiProvider.entries) {
            val request = AiRequest.build(provider, "fake", AiModel("fake", "fake"), listOf("Get out!"), emptyList(),
                target = "ar", playerCharacter = "lucia",
                server = if (provider == AiProvider.LOCAL) LocalAiServer("http://127.0.0.1:1234/v1", "fake") else null)
            assertTrue(provider.id, request.body.contains("Player character: Lucia (a woman)"))
        }
    }

    @Test fun playerCharacterSeparatesCachedTranslationsOnlyForArabic() {
        for (target in listOf("ar", "tr")) {
            val bodies = mutableListOf<String>()
            val cache = TranslationCache()
            fun engine() = AiTranslator(AiProvider.OPENAI, "fake", AiModel("fake", "fake"), null, cache = cache,
                http = Http { _, _, _, body, _ -> bodies.add(body!!); 200 to response("ارحل من هنا.") }, target = target)
            TranslationDialogue.withPlayerCharacter("Jason") { engine().translate("Get out of here.") }
            TranslationDialogue.withPlayerCharacter("Lucia") { engine().translate("Get out of here."); engine().translate("Get out of here.") }
            assertEquals(if (target == "ar") 2 else 1, bodies.size)
            if (target == "ar") {
                assertTrue(user(bodies[0]).contains("Player character: Jason (a man)"))
                assertTrue(user(bodies[1]).contains("Player character: Lucia (a woman)"))
            } else assertFalse(user(bodies[0]).contains("Player character:"))
            TranslationDialogue.withPlayerCharacter(null) { engine().translate("Get out of here.") }
            assertEquals(if (target == "ar") 3 else 1, bodies.size)
        }
    }

    @Test fun similarOcrReadDoesNotReuseAnotherPlayersTranslation() {
        val bodies = mutableListOf<String>()
        val translator = AiTranslator(AiProvider.OPENAI, "fake", AiModel("fake", "fake"), null,
            http = Http { _, _, _, body, _ -> bodies.add(body!!); 200 to response("ارحل من هنا.") }, target = "ar")
        TranslationDialogue.withPlayerCharacter("Jason") { translator.translate("Get out of here.") }
        TranslationDialogue.withPlayerCharacter("Lucia") { translator.translate("Get out of here!") }
        assertEquals(2, bodies.size)
        assertTrue(user(bodies.last()).contains("Player character: Lucia (a woman)"))
    }

    @Test fun contextRemembersTheSameLineForBothPlayers() {
        val bodies = mutableListOf<String>()
        val translator = AiTranslator(AiProvider.OPENAI, "fake", AiModel("fake", "fake"), null,
            http = Http { _, _, _, body, _ ->
                bodies.add(body!!)
                200 to response(if (TranslationDialogue.currentPlayerCharacter == "Jason") "خليك هنا." else "خليكي هنا.")
            }, target = "ar")
        TranslationDialogue.withPlayerCharacter("Jason") { translator.translate("Stay here.") }
        TranslationDialogue.withPlayerCharacter("Lucia") { translator.translate("Stay here."); translator.translate("We should leave now.") }
        val context = user(bodies.last())
        assertEquals(2, Regex("English: Stay here[.]").findAll(context).count())
        assertTrue(context.contains("Arabic: خليك هنا."))
        assertTrue(context.contains("Arabic: خليكي هنا."))
    }

    @Test fun scopeSurvivesFallbackAndIsRestoredAfterNestedFailure() {
        assertNull(TranslationDialogue.currentPlayerCharacter)
        TranslationDialogue.withSpeaker("STRANGER") {
            TranslationDialogue.withPlayerCharacter("jason") {
                try {
                    TranslationDialogue.withPlayerCharacter("lucia") { throw IllegalStateException("test") }
                } catch (_: IllegalStateException) {}
                assertEquals("Jason", TranslationDialogue.currentPlayerCharacter)
                val fallback = TextTranslator {
                    assertEquals("Jason", TranslationDialogue.currentPlayerCharacter)
                    assertEquals("STRANGER", TranslationDialogue.currentSpeaker)
                    "ارحل من هنا."
                }
                val translator = AiTranslator(AiProvider.OPENAI, "fake", AiModel("fake", "fake"), fallback,
                    http = Http { _, _, _, _, _ -> throw IOException("test") }, target = "ar")
                assertEquals("ارحل من هنا.", translator.translate("Get out of here."))
            }
            assertNull(TranslationDialogue.currentPlayerCharacter)
            assertEquals("STRANGER", TranslationDialogue.currentSpeaker)
        }
        assertNull(TranslationDialogue.currentPlayerCharacter)
        assertNull(TranslationDialogue.currentSpeaker)
    }

    @Test fun pipelinePassesInferredOrManualPlayerToAiAndAppliesLiveChanges() {
        for ((target, selection, expected) in listOf(
            Triple("ar", "auto", "Player character: Lucia (a woman)"),
            Triple("ar", "jason", "Player character: Jason (a man)"), Triple("tr", "lucia", null),
        )) {
            val bodies = CopyOnWriteArrayList<String>()
            val executor = Executors.newSingleThreadExecutor()
            var time = 0L
            val translator = AiTranslator(AiProvider.OPENAI, "fake", AiModel("fake", "fake"), null,
                http = Http { _, _, _, body, _ -> bodies.add(body!!); 200 to response("ترجمة الكلام.") }, target = target)
            val sender = object : SubtitleSender {
                override fun sendSubtitle(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) {}
                override fun clear() {}
            }
            val pipeline = SubtitlePipeline(translator, sender, { time }, executor)
            assertEquals("auto", pipeline.playerCharacter)
            pipeline.translationLanguage = target; pipeline.skipsTurkish = target == "tr"; pipeline.playerCharacter = selection
            fun feed(text: String) {
                repeat(25) {
                    pipeline.process(Reading.fromText(text), CropRegion().box)
                    executor.submit {}.get(5, TimeUnit.SECONDS)
                    time += 100
                }
            }
            try {
                for (line in listOf("LUCIA: I'm ready to go.", "LUCIA: Keep the engine running.", "LUCIA: We leave at dawn.")) feed(line)
                assertEquals(3, bodies.size)
                if (selection == "auto") assertFalse(user(bodies.first()).contains("Player character:"))
                if (expected == null) assertFalse(user(bodies.last()).contains("Player character:"))
                else assertTrue(user(bodies.last()).contains(expected))
                if (target == "ar" && selection == "auto") {
                    pipeline.playerCharacter = "jason"
                    feed("STRANGER: Get out of my car!")
                    assertTrue(user(bodies.last()).contains("Player character: Jason (a man)"))
                    pipeline.playerCharacter = "auto"
                    feed("STRANGER: Watch where you're going!")
                    assertTrue(user(bodies.last()).contains("Player character: Lucia (a woman)"))
                }
                assertNull(executor.submit<String?> { TranslationDialogue.currentPlayerCharacter }.get(5, TimeUnit.SECONDS))
                assertNull(executor.submit<String?> { TranslationDialogue.currentSpeaker }.get(5, TimeUnit.SECONDS))
            } finally { pipeline.shutdown() }
        }
    }

    @Test fun packedSpeakerInformsLaterStrangersLineWithoutChangingPackLookup() {
        for (target in listOf("ar", "tr")) {
            val bodies = CopyOnWriteArrayList<String>()
            val lookedUp = mutableListOf<String>()
            val packed = listOf("We should leave this place.", "Grab the bag.", "Stay close to me.")
            val executor = Executors.newSingleThreadExecutor()
            var time = 0L
            val translator = AiTranslator(AiProvider.OPENAI, "fake", AiModel("fake", "fake"), null,
                http = Http { _, _, _, body, _ -> bodies.add(body!!); 200 to response("ارحل من هنا.") }, target = target)
            val sender = object : SubtitleSender {
                override fun sendSubtitle(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) {}
                override fun clear() {}
            }
            val pipeline = SubtitlePipeline(translator, sender, { time }, executor).apply {
                translationLanguage = target; skipsTurkish = target == "tr"
                packLookup = { text -> lookedUp.add(text); if (text in packed) PackMatch("ترجمة جاهزة.") else null }
            }
            fun feed(text: String) {
                repeat(25) {
                    pipeline.process(Reading.fromText(text), CropRegion().box)
                    executor.submit {}.get(5, TimeUnit.SECONDS)
                    time += 100
                }
            }
            try {
                for (body in packed) feed("LUCIA: $body")
                assertTrue(bodies.isEmpty())
                assertEquals(3, pipeline.packHits)
                assertTrue(lookedUp.contains("LUCIA: We should leave this place."))
                assertTrue(lookedUp.contains("We should leave this place."))
                feed("STRANGER: Get out of here!")
                assertEquals(1, bodies.size)
                if (target == "ar") assertTrue(user(bodies.single()).contains("Player character: Lucia (a woman)"))
                else assertFalse(user(bodies.single()).contains("Player character:"))
            } finally { pipeline.shutdown() }
        }
    }
}
