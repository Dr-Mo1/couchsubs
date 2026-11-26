// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.account

import com.couchsubs.mobile.remoteplay.DisconnectKind
import com.couchsubs.mobile.translation.Http
import com.couchsubs.mobile.translation.TargamgiGate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ActivityLogTest {
    private val device = ActivityDeviceInfo(
        deviceId = "device-12345",
        appVersion = "1.0.48",
        deviceModel = "Google Pixel 9",
        androidVersion = "15 (API 35)",
    )

    @Test
    fun queueEnforces100EventBatchCapAndDrainsAcrossBatches() {
        val dir = Files.createTempDirectory("activity-batch").toFile()
        try {
            val file = File(dir, "events.json")
            val queue = ActivityQueue(file)
            for (i in 1..150) {
                assertTrue(queue.enqueue(ActivityEvent("screen", "2026-11-26T12:00:00Z", mapOf("screen" to "s$i"))))
            }
            assertEquals(150, queue.size)
            assertEquals(100, queue.peekBatch().size)

            val sentSizes = mutableListOf<Int>()
            val sentPayloads = mutableListOf<JSONObject>()
            var now = 10_000L
            val http = Http { method, url, headers, body, _ ->
                assertEquals("POST", method)
                assertEquals("${TargamgiGate.SUPABASE_URL}/functions/v1/events", url)
                assertEquals(TargamgiGate.PUBLISHABLE_KEY, headers["apikey"])
                assertEquals("Bearer tok-1", headers["Authorization"])
                val json = JSONObject(body.orEmpty())
                sentPayloads += json
                val count = json.getJSONArray("events").length()
                sentSizes += count
                200 to """{"ok":true,"stored":$count}"""
            }
            val client = ActivityClient(
                queue = queue,
                http = http,
                deviceInfo = device,
                token = { "tok-1" },
                refresh = { null },
                ensureAccount = { false },
                isConsented = { true },
                clock = { now },
            )

            // First batch sends at most 100 events.
            assertEquals(FlushResult.Sent(100, 50), client.flushOnce())
            assertEquals(listOf(100), sentSizes)
            assertEquals("device-12345", sentPayloads[0].getString("device_id"))
            assertEquals("1.0.48", sentPayloads[0].getString("app_version"))
            assertEquals("Google Pixel 9", sentPayloads[0].getString("device_model"))
            assertEquals("15 (API 35)", sentPayloads[0].getString("android_version"))
            assertEquals("s1", sentPayloads[0].getJSONArray("events").getJSONObject(0).getJSONObject("data").getString("screen"))
            assertEquals("s100", sentPayloads[0].getJSONArray("events").getJSONObject(99).getJSONObject("data").getString("screen"))

            // Immediate second call respects the 5 s server rate limit without dropping events.
            assertEquals(FlushResult.RateLimited, client.flushOnce())
            assertEquals(50, queue.size)

            // After the gap, the remaining 50 events are sent.
            now += ActivityRules.MIN_SEND_GAP_MS
            assertEquals(FlushResult.Sent(50, 0), client.flushOnce())
            assertEquals(listOf(100, 50), sentSizes)
            assertEquals("s101", sentPayloads[1].getJSONArray("events").getJSONObject(0).getJSONObject("data").getString("screen"))
            assertEquals(0, queue.size)
            assertEquals(FlushResult.Empty, client.flushOnce())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun queueSurvivesProcessRestartViaPrivateFile() {
        val dir = Files.createTempDirectory("activity-restart").toFile()
        try {
            val file = File(dir, "events.json")
            val beforeRestart = ActivityQueue(file)
            beforeRestart.enqueue(ActivityEvent("app_open", "2026-11-26T12:00:00Z"))
            beforeRestart.enqueue(ActivityEvent("translate_start", "2026-11-26T12:00:05Z", mapOf("style" to "street", "game" to "GTA VI")))
            beforeRestart.enqueue(ActivityEvent("translate_stop", "2026-11-26T12:05:05Z", mapOf("seconds" to 300, "lines" to 42, "style" to "street")))

            // Simulate app restart: new queue reads the persisted file.
            val afterRestart = ActivityQueue(file)
            assertEquals(3, afterRestart.size)
            val batch = afterRestart.peekBatch()
            assertEquals(listOf("app_open", "translate_start", "translate_stop"), batch.map { it.type })
            assertEquals("GTA VI", batch[1].data["game"])
            assertEquals(42, batch[2].data["lines"])

            // Acknowledging removes persisted events so they are not re-sent after another restart.
            afterRestart.acknowledge(2)
            val afterSecondRestart = ActivityQueue(file)
            assertEquals(1, afterSecondRestart.size)
            assertEquals("translate_stop", afterSecondRestart.peekBatch().single().type)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun eventsAreNotSentUntilPrivacyNoticeIsAccepted() {
        var calls = 0
        var consented = false
        val queue = ActivityQueue(null)
        queue.enqueue(ActivityEvent("app_open", "2026-11-26T12:00:00Z"))
        val client = ActivityClient(
            queue = queue,
            http = Http { _, _, _, _, _ -> calls++; 200 to """{"ok":true,"stored":1}""" },
            deviceInfo = device,
            token = { "tok" },
            refresh = { null },
            ensureAccount = { false },
            isConsented = { consented },
            clock = { 10_000L },
        )

        assertEquals(FlushResult.ConsentPending, client.flushOnce())
        assertEquals(0, calls)
        assertEquals(1, queue.size)

        consented = true
        assertEquals(FlushResult.Sent(1, 0), client.flushOnce())
        assertEquals(1, calls)
    }

    @Test
    fun rateLimit429AndNetworkErrorsNeverDropEventsAnd403AccountNotFoundCallsStatusFirst() {
        var now = 10_000L
        var ensureCalls = 0
        val replies = ArrayDeque(
            listOf(
                429 to """{"error":"too_frequent"}""",
                403 to """{"error":"account_not_found"}""",
                200 to """{"ok":true,"stored":1}""",
            ),
        )
        val queue = ActivityQueue(null)
        queue.enqueue(ActivityEvent("heartbeat", "2026-11-26T12:00:00Z", mapOf("style" to "clean")))

        val client = ActivityClient(
            queue = queue,
            http = Http { _, _, _, _, _ -> replies.removeFirst() },
            deviceInfo = device,
            token = { "tok" },
            refresh = { null },
            ensureAccount = { ensureCalls++; true },
            isConsented = { true },
            clock = { now },
        )

        // 429 too_frequent: kept in queue and deferred.
        assertEquals(FlushResult.RateLimited, client.flushOnce())
        assertEquals(1, queue.size)

        // Advance past 5 s backoff -> 403 account_not_found -> ensureAccount() -> retry -> 200 OK.
        now += ActivityRules.MIN_SEND_GAP_MS
        assertEquals(FlushResult.Sent(1, 0), client.flushOnce())
        assertEquals(1, ensureCalls)
        assertEquals(0, queue.size)

        // Offline failure also keeps events in queue.
        queue.enqueue(ActivityEvent("update_check", "2026-11-26T12:01:00Z", mapOf("manual" to true)))
        val offlineClient = ActivityClient(
            queue = queue,
            http = Http { _, _, _, _, _ -> throw IOException("offline") },
            deviceInfo = device,
            token = { "tok" },
            refresh = { null },
            ensureAccount = { false },
            isConsented = { true },
            clock = { now + 20_000L },
        )
        assertEquals(FlushResult.RetryLater(null), offlineClient.flushOnce())
        assertEquals(1, queue.size)
    }

    @Test
    fun sanitizationRespectsLimitsAndNeverLeaksPersonalOrIpData() {
        val queue = ActivityQueue(null)
        assertFalse(queue.enqueue(ActivityEvent("unknown_event", "2026-11-26T12:00:00Z")))

        // Error message capped at 300 chars and stripped of newlines.
        val longMsg = "Line1\n" + "x".repeat(400)
        val sanitized = ActivityRules.sanitizeErrorMessage(longMsg)
        assertEquals(300, sanitized.length)
        assertFalse(sanitized.contains('\n'))

        // Oversized event data capped to <= 2 KB.
        val hugeData = mapOf("a" to "y".repeat(3000), "b" to "z".repeat(3000))
        val capped = ActivityRules.capData(hugeData)
        assertTrue(JSONObject(capped).toString().toByteArray(Charsets.UTF_8).size <= ActivityRules.MAX_DATA_BYTES)

        // PS5 error code strips host IP from NOT_FOUND while preserving safe hex codes for REJECTED.
        assertEquals("not_found", ActivityRules.safePs5ErrorCode(DisconnectKind.NOT_FOUND, "192.168.1.42"))
        assertEquals("rejected_0x80108b12", ActivityRules.safePs5ErrorCode(DisconnectKind.REJECTED, "0x80108B12"))
        assertEquals("rejected", ActivityRules.safePs5ErrorCode(DisconnectKind.REJECTED, "user@example.com"))

        // Personal/secret keys are never in ALLOWED_SETTING_KEYS.
        for (secretKey in listOf("accountId", "console", "loginPasscode", "xboxUserToken", "aiInstruction", "tvName")) {
            assertFalse(secretKey, secretKey in ActivityRules.ALLOWED_SETTING_KEYS)
        }
    }
}
