// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2 item 4: capture to display, with the same numbers the app logs in [ShownPair] (ocrMs, translationMs, totalMs).
 * Frames arrive every [FRAME_MS] (the app reads at most 5 frames a second), OCR takes [OCR_MS], the engine takes
 * [TRANSLATE_MS]; the clock is fake, so the figures are exactly what the pipeline itself adds (no device noise).
 * The figures are printed as `LATENCY ...` lines; the asserts pin the "after" numbers.
 */
class PipelineLatencyTest {
    companion object {
        const val FRAME_MS = 200
        const val OCR_MS = 150
        const val TRANSLATE_MS = 600
    }

    private class Inline : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class Shown(val text: String, val replaces: Boolean, val at: Long)

    private class Run {
        var now = 0L
        val shown = mutableListOf<Shown>()
        val pairs = mutableListOf<ShownPair>()
        val requested = mutableListOf<String>()
        val pipeline = SubtitlePipeline(
            TextTranslator { text -> requested.add(text); now += TRANSLATE_MS; "ar:$text" },
            object : SubtitleSender {
                override fun sendSubtitle(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) { shown.add(Shown(text, replaces, now)) }
                override fun clear() {}
            },
            { now }, Inline(),
        ).apply { holdReadings = true; onPairShown = { pairs.add(it) } }

        /**
         * One frame captured at [capturedAt]; the reading reaches the pipeline after the OCR time. The translation runs
         * inline, so while it runs the OCR thread of this model waits (time never goes backwards); that is the same
         * before and after a change, so the difference between two runs is exactly what the pipeline adds or saves.
         */
        fun frame(text: String, capturedAt: Long) {
            now = maxOf(now, capturedAt + OCR_MS)
            pipeline.process(Reading.fromText(text), CropRegion().box, null, capturedAt, OCR_MS)
        }

        /** [text] stays on screen from [from] for [frames] frames; returns when the pipeline first shows [expected]. */
        fun hold(text: String, from: Long, frames: Int) { for (i in 0 until frames) frame(text, from + i * FRAME_MS) }
    }

    private fun report(name: String, run: Run, firstSeen: Long, finalText: String) {
        val display = run.shown.firstOrNull { it.text == "ar:$finalText" }?.at
        println("LATENCY $name: finalShownAt=${display?.minus(firstSeen)}ms requests=${run.requested.size} " +
            "lines=${run.shown.size} replaces=${run.shown.map { it.replaces }} totals=${run.pairs.map { it.totalMs }}")
    }

    /** A whole sentence with a full stop goes at once: the pipeline adds nothing. */
    @Test fun completeSentenceAddsNoWait() {
        val run = Run()
        run.hold("Hold the gate.", 0, 4)
        report("complete", run, 0, "Hold the gate.")
        assertEquals(OCR_MS + TRANSLATE_MS, run.shown.single().at.toInt())
    }

    /** A line that looks unfinished is held for the partial-line wait; the time the line was already stable counts. */
    @Test fun unfinishedLineDoesNotWaitTwice() {
        val run = Run()
        run.hold("Get Franklin close to the", 0, 8)
        report("unfinished", run, 0, "Get Franklin close to the")
        val at = run.shown.single().at
        // wait = 300 ms from the first sighting (frame at 0); frames are 200 ms apart, so the 2nd stable frame is at 200 and
        // the next frame at 400 is the first one past the wait. Before: the wait started at 200 and ended on the frame at 600.
        assertTrue("shown at $at", at <= 400 + OCR_MS + TRANSLATE_MS)
    }

    /**
     * A slow typewriter: the fragment stays long enough to be shown, then the whole line arrives. Before A2 the whole line
     * was a second line (replaces=false: the phone stacked "previous" and "current"); now it takes the fragment's place.
     */
    @Test fun slowTypewriterFragmentIsReplacedNotStacked() {
        val run = Run()
        run.hold("Time to re", 0, 5)
        run.hold("Time to recover.", 5L * FRAME_MS, 4)
        report("slow-typewriter", run, 5L * FRAME_MS, "Time to recover.")
        assertEquals(listOf(false, true), run.shown.map { it.replaces })
        assertEquals(listOf(false, true), run.pairs.map { it.replaces })
    }

    /** Letter by letter text: one line on screen at the end, the last display is the whole sentence. */
    @Test fun typewriterEndsWithTheWholeLineAndReplacesInPlace() {
        val run = Run()
        val steps = listOf("Time to re", "Time to re", "Time to rec", "Time to recov", "Time to recover", "Time to recover")
        steps.forEachIndexed { i, text -> run.frame(text, i * FRAME_MS.toLong()) }
        run.hold("Time to recover", steps.size * FRAME_MS.toLong(), 4)
        report("typewriter", run, 4L * FRAME_MS, "Time to recover")
        assertEquals("ar:Time to recover", run.shown.last().text)
        assertTrue("every display after the first replaces the one before: ${run.shown.map { it.replaces }}",
            run.shown.drop(1).all { it.replaces })
    }
}
