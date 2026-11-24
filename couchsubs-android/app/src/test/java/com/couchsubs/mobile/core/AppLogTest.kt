// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import com.couchsubs.mobile.reporting.ReportLog
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppLogTest {

    @Test
    fun ringBufferRespectsCapacityAndPreservesChronologicalOrder() {
        val buffer = AppLogBuffer(maxLines = 5)
        for (i in 1..8) buffer.append("line-$i")
        assertEquals(listOf("line-4", "line-5", "line-6", "line-7", "line-8"), buffer.memoryLines())
        assertEquals("line-4\nline-5\nline-6\nline-7\nline-8\n", buffer.readAll())
    }

    @Test
    fun defaultRingBufferHoldsUpToThreeThousandLines() {
        val buffer = AppLogBuffer()
        for (i in 1..3050) buffer.append("entry-$i")
        val lines = buffer.memoryLines()
        assertEquals(3000, lines.size)
        assertEquals("entry-51", lines.first())
        assertEquals("entry-3050", lines.last())
    }

    @Test
    fun rotationKeepsCurrentAndOnePreviousAndReadsOldestToNewestWithMemory() {
        val dir = Files.createTempDirectory("applog-rotate").toFile()
        try {
            // Each line "line-XX\n" is 8 bytes; maxFileBytes = 24 fits 3 lines per file.
            val buffer = AppLogBuffer(initialDir = dir, maxLines = 100, maxFileBytes = 24L)
            for (i in 1..9) buffer.append("line-%02d".format(i))
            buffer.flushSync()

            // After 9 lines (3 rotations of 3 lines each):
            // lines 1..3 rotated out, previous.log has 4..6, current.log has 7..9.
            val current = File(dir, AppLogBuffer.CURRENT_FILE)
            val previous = File(dir, AppLogBuffer.PREVIOUS_FILE)
            assertTrue(current.isFile)
            assertTrue(previous.isFile)
            assertEquals(setOf(AppLogBuffer.CURRENT_FILE, AppLogBuffer.PREVIOUS_FILE), dir.list()!!.toSet())
            assertEquals("line-04\nline-05\nline-06\n", previous.readText())
            assertEquals("line-07\nline-08\nline-09\n", current.readText())

            // Append two more lines without flushing: readAll must include previous + current + memory, newest last, no duplicates.
            buffer.append("line-10")
            buffer.append("line-11")
            assertEquals(
                "line-04\nline-05\nline-06\nline-07\nline-08\nline-09\nline-10\nline-11\n",
                buffer.readAll(),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun attachedLogRespectsCapAndAppendsLogcatAndScrubs() {
        val bigAppLog = "app line\n".repeat(20_000) + "NEW_APP_TAIL"
        val onlyApp = ReportLog.combine(bigAppLog, "", cap = 100)
        assertEquals(100, onlyApp.length)
        assertTrue(onlyApp.endsWith("NEW_APP_TAIL"))

        val withLogcat = ReportLog.combine("app-line-1\n", "logcat-line-1\n", cap = 200_000)
        assertTrue(withLogcat.indexOf("app-line-1") < withLogcat.indexOf("logcat-line-1"))

        val appChunk = "app log line\n".repeat(16_000) // 208,000 chars
        val logcatChunk = "logcat line\n".repeat(2_000) // 24,000 chars
        val cappedWithLogcat = ReportLog.combine(appChunk, logcatChunk, cap = 200_000)
        assertEquals(200_000, cappedWithLogcat.length)
        assertTrue(cappedWithLogcat.endsWith(logcatChunk))
        assertTrue(cappedWithLogcat.contains("--- logcat ---"))

        val scrubbed = ReportLog.combine(
            "token=secret123 user@example.com",
            "Authorization: Bearer abcdef123456 password=hunter2",
            cap = 200_000,
        )
        assertFalse(scrubbed.contains("secret123"))
        assertFalse(scrubbed.contains("user@example.com"))
        assertFalse(scrubbed.contains("abcdef123456"))
        assertFalse(scrubbed.contains("hunter2"))
        assertTrue(scrubbed.contains("token=***"))
        assertTrue(scrubbed.contains("***@***"))
        assertTrue(scrubbed.contains("Bearer ***"))
        assertTrue(scrubbed.contains("password=***"))
    }

    @Test
    fun facadeSurvivesAndroidLogThrowingInJvmTests() {
        val originalLog = Log.platformLog
        val originalIsLoggable = Log.platformIsLoggable
        Log.clearMemory()
        try {
            Log.platformLog = { _, _, _, _ -> throw RuntimeException("Method in android.util.Log not mocked") }
            Log.platformIsLoggable = { _, _ -> throw RuntimeException("Method isLoggable not mocked") }

            assertFalse(Log.isLoggable("Tag", Log.INFO))
            assertEquals(0, Log.v("TagV", "verbose msg"))
            assertEquals(0, Log.d("TagD", "debug msg"))
            assertEquals(0, Log.i("TagI", "info msg"))
            assertEquals(0, Log.w("TagW", "warn msg"))
            assertEquals(0, Log.e("TagE", "error msg", IllegalStateException("boom")))

            val lines = Log.memoryLines()
            assertEquals(5, lines.size)
            assertTrue(lines[0].contains("V/TagV: verbose msg"))
            assertTrue(lines[1].contains("D/TagD: debug msg"))
            assertTrue(lines[2].contains("I/TagI: info msg"))
            assertTrue(lines[3].contains("W/TagW: warn msg"))
            assertTrue(lines[4].contains("E/TagE: error msg"))
            assertTrue(lines[4].contains("IllegalStateException: boom"))
            assertTrue(lines[4].contains("at "))
        } finally {
            Log.platformLog = originalLog
            Log.platformIsLoggable = originalIsLoggable
            Log.clearMemory()
        }
    }

    @Test
    fun bufferIsThreadSafeUnderConcurrentAppendsAndFlushes() {
        val dir = Files.createTempDirectory("applog-threads").toFile()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val buffer = AppLogBuffer(initialDir = dir, maxLines = 3000, maxFileBytes = 1024L * 1024L)
            val latch = CountDownLatch(4)
            for (t in 0 until 4) {
                pool.execute {
                    try {
                        for (i in 0 until 200) {
                            buffer.append("t$t-$i")
                            if (i % 50 == 0) buffer.flushSync()
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            buffer.flushSync()
            val allLines = buffer.readAll().trimEnd('\n').lines()
            assertEquals(800, allLines.size)
        } finally {
            pool.shutdownNow()
            dir.deleteRecursively()
        }
    }
}
