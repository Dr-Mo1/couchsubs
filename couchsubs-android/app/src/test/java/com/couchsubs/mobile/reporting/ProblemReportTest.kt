// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.reporting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProblemReportTest {
    private val info = ReportInfo("1.0.41", 41, "Maker Phone", "16 (API 36)", "test-install")
    private fun line(id: Long) = ReviewLine(id, "gta-6", "Hello \"friend\"\nNext line", "أهلًا يا صاحبي", "pack", "2026-11-24T09:00:00Z")

    @Test fun everyCredentialNameIsScrubbedCaseInsensitively() {
        for (name in listOf("code", "token", "access_token", "refresh_token", "id_token", "npsso", "password", "secret", "key")) {
            assertEquals("$name=***&next=ok", ReportScrubber.scrub("$name=private&next=ok"))
            assertEquals("${name.uppercase()}=***", ReportScrubber.scrub("${name.uppercase()}=private"))
            assertEquals("$name=***", ReportScrubber.scrub("$name=\"two words\"".replace("\\\"", "\"")))
        }
    }

    @Test fun jsonCredentialsAndBearerAreScrubbed() {
        assertEquals("{\"token\":\"***\"}", ReportScrubber.scrub("{\"token\":\"private\"}"))
        assertEquals("Authorization: Bearer ***", ReportScrubber.scrub("Authorization: Bearer abc.def-123"))
    }

    @Test fun emailsAndLongTokensAreScrubbedButArabicIsUntouched() {
        val arabic = "إحنا رايحين دلوقتي. خليك قريب مني."
        assertEquals(arabic, ReportScrubber.scrub(arabic))
        assertEquals("***@***", ReportScrubber.scrub("me.test+tag@example.com"))
        for (token in listOf("a".repeat(32), "aB8+/".repeat(8), "1234567890abcdef".repeat(3), "eyJ" + "X_-".repeat(20))) {
            assertEquals("***", ReportScrubber.scrub(token))
        }
        assertEquals("a".repeat(31), ReportScrubber.scrub("a".repeat(31)))
    }

    @Test fun scrubberIsIdempotent() {
        val text = "password=private Bearer abc me@example.org ${"c".repeat(40)} قديم"
        assertEquals(ReportScrubber.scrub(text), ReportScrubber.scrub(ReportScrubber.scrub(text)))
    }

    @Test fun jsonRoundTripsQuotesNewlinesArabicAndOmitsEmptyName() {
        val message = "قال: \"أهلًا\"\nالسطر الثاني"
        val json = JSONObject(ReportJson.build("user", message, "  ", "أهلًا\n\"نص\"", info).toString())
        assertEquals(message, json.getString("message"))
        assertEquals("أهلًا\n\"نص\"", json.getString("log"))
        assertFalse(json.has("name"))
        assertEquals(setOf("kind", "message", "app_version", "version_code", "device", "android", "install_id", "log"), json.keySet())
    }

    @Test fun reportBoundsAreAppliedAfterScrubbing() {
        val json = ReportJson.build("error", "ع".repeat(5000), "ع".repeat(90), "قديم" + "ع".repeat(200001), info)
        assertEquals(4000, json.getString("message").length)
        assertEquals(80, json.getString("name").length)
        assertEquals("ع".repeat(200000), json.getString("log"))
        assertEquals(8000, ReportJson.build("crash", "ع".repeat(9000), null, "", info, 8000).getString("message").length)
        assertTrue(ReportJson.build("error", " ", null, "", info).getString("message").isNotBlank())
    }

    @Test fun limiterAllowsFirstAndExactlyThirtyMinutesAndRejectsClockRollback() {
        var now = 1000L
        val limiter = ReportLimiter { now }
        assertTrue(limiter.allowed(null))
        val stored = limiter.now()
        now += 30 * 60 * 1000 - 1
        assertFalse(limiter.allowed(stored))
        now++
        assertTrue(limiter.allowed(stored))
        now = 0
        assertFalse(limiter.allowed(stored))
        // Recreating the limiter with a persisted timestamp keeps the limit across launches.
        assertFalse(ReportLimiter { 1100L }.allowed(stored))
    }

    @Test fun queueSurvivesReopeningAndDropsOnlyTheOldestBeyondTwenty() {
        val directory = Files.createTempDirectory("problem-reports").toFile()
        try {
            val queue = PendingReports(directory)
            val saved = (1..23).map { queue.save(JSONObject().put("message", "report $it")) }
            val pending = PendingReports(directory).files()
            assertEquals(saved.takeLast(20), pending)
            assertTrue(saved.take(3).all { !it.exists() })
            assertEquals("report 23", JSONObject(pending.last().readText()).getString("message"))
            assertTrue(directory.listFiles()!!.none { it.extension == "tmp" })
        } finally { directory.deleteRecursively() }
    }

    @Test fun batchingIsOrderedAndNeverExceedsTwoHundred() {
        val batches = ReviewBatch.pending((1L..405L).map(::line).reversed(), 0)
        assertEquals(listOf(200, 200, 5), batches.map { it.size })
        assertEquals((1L..405L).toList(), batches.flatten().map { it.id })
    }

    @Test fun cursorAdvancesOnlyForAcknowledgedBatchesAndPreventsRepeatUploads() {
        val all = (1L..405L).map(::line)
        val first = ReviewBatch.pending(all, 0).first()
        assertEquals(0L, ReviewBatch.acknowledged(0, first, false))
        val cursor = ReviewBatch.acknowledged(0, first, true)
        assertEquals(200L, cursor)
        val remaining = ReviewBatch.pending(all, cursor)
        assertEquals(201L, remaining.first().first().id)
        val completed = ReviewBatch.acknowledged(cursor, remaining.flatten(), true)
        assertTrue(ReviewBatch.pending(all, completed).isEmpty())
        assertEquals(completed, ReviewBatch.acknowledged(completed, emptyList(), true))
    }

    @Test fun lineArrayUsesExactFieldsAndScrubsOnlyOutgoingText() {
        val entry = line(1).copy(source = "code=private me@example.org", arabic = "أهلًا", origin = "ع".repeat(30))
        val json = JSONArray(ReviewBatch.json(listOf(entry), info).toString())
        assertEquals(1, json.length())
        val row = json.getJSONObject(0)
        assertEquals(setOf("install_id", "game", "source_text", "arabic_text", "origin", "shown_at", "app_version"), row.keySet())
        assertEquals("code=*** ***@***", row.getString("source_text"))
        assertEquals("أهلًا", row.getString("arabic_text"))
        assertEquals(20, row.getString("origin").length)
        assertEquals("gta-6", row.getString("game"))
        assertEquals("test-install", row.getString("install_id"))
        assertEquals("2026-11-24T09:00:00Z", row.getString("shown_at"))
        val quoted = ReviewBatch.json(listOf(line(2)), info).getJSONObject(0)
        assertEquals(line(2).source, quoted.getString("source_text"))
    }

    @Test fun lineTextBoundsAndEmptyArray() {
        val entry = line(1).copy(source = "ع".repeat(2100), arabic = "ع".repeat(2100))
        val row = ReviewBatch.json(listOf(entry), info).getJSONObject(0)
        assertEquals(2000, row.getString("source_text").length)
        assertEquals(2000, row.getString("arabic_text").length)
        assertEquals(0, ReviewBatch.json(emptyList(), info).length())
    }
}
