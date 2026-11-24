// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.reporting

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ProblemReportTest {
    @Test(timeout = 2000) fun longNonEmailLogDoesNotRescanEverySuffix() {
        val arabic = "ن".repeat(250000)
        assertEquals(arabic, ReportScrubber.scrub(arabic))
        assertEquals("***", ReportScrubber.scrub("a".repeat(250000)))
        assertEquals("***@***", ReportScrubber.scrub("a".repeat(100000) + "@example.com"))
    }
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

    @Test fun startReportBuiltOncePerVersionCodeOnBetaOnly() {
        assertNull(StartReport.buildIfNeeded("stable", 0, "1.0.41", 41, info))
        val first = StartReport.buildIfNeeded("beta", 0, "1.0.41", 41, info)!!
        assertEquals("start", first.getString("kind"))
        assertEquals("started 1.0.41 (41)", first.getString("message"))
        assertEquals("", first.getString("log"))
        assertFalse(first.has("name"))
        assertEquals(41, first.getInt("version_code"))

        // Same versionCode is not reported a second time.
        assertNull(StartReport.buildIfNeeded("beta", 41, "1.0.41", 41, info))

        // A newer versionCode builds a new start report.
        val nextInfo = info.copy(version = "1.0.42", versionCode = 42)
        val second = StartReport.buildIfNeeded("beta", 41, "1.0.42", 42, nextInfo)!!
        assertEquals("start", second.getString("kind"))
        assertEquals("started 1.0.42 (42)", second.getString("message"))
        assertEquals("", second.getString("log"))
    }

    @Test fun mediaObjectPathAndExtensionMatchAllowedMimeTypes() {
        val cases = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "video/mp4" to "mp4",
            "video/webm" to "webm",
            "video/3gpp" to "3gp",
            "video/quicktime" to "mov",
            "IMAGE/PNG; charset=utf-8" to "png",
        )
        for ((mime, ext) in cases) {
            assertEquals(ext, ReportMedia.extensionFor(mime))
            assertEquals("install-1/uuid-1.$ext", ReportMedia.objectPath("install-1", mime, "uuid-1"))
        }
        assertNull(ReportMedia.extensionFor("image/gif"))
        assertNull(ReportMedia.extensionFor("application/pdf"))
        assertNull(ReportMedia.extensionFor(null))
        assertNull(ReportMedia.objectPath("install-1", "image/gif", "uuid-1"))
        assertNull(ReportMedia.objectPath(" ", "image/png", "uuid-1"))
    }

    @Test fun mediaValidationChecksMimeTypeAndFiftyMegabyteLimit() {
        assertNull(ReportMedia.validate("image/jpeg", 0L))
        assertNull(ReportMedia.validate("video/mp4", ReportMedia.MAX_BYTES))
        assertEquals(MediaError.TOO_LARGE, ReportMedia.validate("video/mp4", ReportMedia.MAX_BYTES + 1L))
        assertEquals(MediaError.TOO_LARGE, ReportMedia.validate("image/png", -1L))
        assertEquals(MediaError.UNSUPPORTED_TYPE, ReportMedia.validate("image/gif", 1024L))
        assertEquals(MediaError.UNSUPPORTED_TYPE, ReportMedia.validate(null, 1024L))
        assertEquals(MediaError.UNSUPPORTED_TYPE, ReportMedia.validate("application/octet-stream", ReportMedia.MAX_BYTES + 1L))
    }

    @Test fun reportJsonIncludesMediaOnlyWhenProvidedAndCapsAtThree() {
        val withoutMedia = ReportJson.build("user", "مشكلة", null, "", info)
        assertFalse(withoutMedia.has("media"))

        val withBlankMedia = ReportJson.build("user", "مشكلة", null, "", info, media = listOf("", "  "))
        assertFalse(withBlankMedia.has("media"))

        val paths = listOf(
            "test-install/11111111-1111-1111-1111-111111111111.png",
            "test-install/22222222-2222-2222-2222-222222222222.mp4",
            "test-install/33333333-3333-3333-3333-333333333333.jpg",
            "test-install/44444444-4444-4444-4444-444444444444.webp",
        )
        val withMedia = JSONObject(ReportJson.build("user", "مشكلة", null, "", info, media = paths).toString())
        assertTrue(withMedia.has("media"))
        val mediaArray = withMedia.getJSONArray("media")
        assertEquals(3, mediaArray.length())
        // UUID object paths must not be scrubbed by ReportScrubber.
        assertEquals(paths[0], mediaArray.getString(0))
        assertEquals(paths[1], mediaArray.getString(1))
        assertEquals(paths[2], mediaArray.getString(2))
    }

    @Test fun uploadAllSkipsAlreadyUploadedFilesOnRetry() {
        val items = listOf("content://media/1", "content://media/2", "content://media/3")
        val attemptedFirst = mutableListOf<String>()
        val progressFirst = mutableListOf<Pair<Int, Int>>()

        // First attempt: item 1 succeeds, item 2 fails.
        val (ok1, uploaded1) = ReportMedia.uploadAll(
            items = items,
            alreadyUploaded = emptyMap(),
            key = { it },
            onProgress = { cur, total -> progressFirst.add(cur to total) },
        ) { uri ->
            attemptedFirst.add(uri)
            if (uri == items[0]) "install/1.png" else null
        }
        assertFalse(ok1)
        assertEquals(listOf(items[0], items[1]), attemptedFirst)
        assertEquals(listOf(1 to 3, 2 to 3), progressFirst)
        assertEquals(mapOf(items[0] to "install/1.png"), uploaded1)

        // Retry: item 1 is skipped; items 2 and 3 are uploaded.
        val attemptedRetry = mutableListOf<String>()
        val progressRetry = mutableListOf<Pair<Int, Int>>()
        val (ok2, uploaded2) = ReportMedia.uploadAll(
            items = items,
            alreadyUploaded = uploaded1,
            key = { it },
            onProgress = { cur, total -> progressRetry.add(cur to total) },
        ) { uri ->
            attemptedRetry.add(uri)
            when (uri) {
                items[1] -> "install/2.mp4"
                items[2] -> "install/3.jpg"
                else -> fail("Should not re-upload $uri").let { null }
            }
        }
        assertTrue(ok2)
        assertEquals(listOf(items[1], items[2]), attemptedRetry)
        assertEquals(listOf(1 to 2, 2 to 2), progressRetry)
        assertEquals(
            mapOf(
                items[0] to "install/1.png",
                items[1] to "install/2.mp4",
                items[2] to "install/3.jpg",
            ),
            uploaded2,
        )

        // Third retry when everything already succeeded (e.g., report POST failed): zero uploads.
        val attemptedThird = mutableListOf<String>()
        val (ok3, uploaded3) = ReportMedia.uploadAll(
            items = items,
            alreadyUploaded = uploaded2,
            key = { it },
        ) { uri ->
            attemptedThird.add(uri)
            "unexpected"
        }
        assertTrue(ok3)
        assertTrue(attemptedThird.isEmpty())
        assertEquals(uploaded2, uploaded3)
    }
}
