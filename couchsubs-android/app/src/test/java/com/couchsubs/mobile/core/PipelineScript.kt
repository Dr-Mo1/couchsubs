// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** Translations run on the calling thread, so a test is a plain script of frames. */
internal class InlineExecutor : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() {}
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

/**
 * A pipeline with a fake clock, inline translation (the engine answers `ar:<text>` unless [translate] says otherwise) and a
 * log of everything that reached the screen: [events] has each display as its text and each clear as `<clear>`.
 */
internal class PipelineScript(translate: (String) -> String = { "ar:$it" }) {
    class Display(val text: String, val replaces: Boolean, val at: Long)

    var now = 0L
    val displays = mutableListOf<Display>()
    val events = mutableListOf<String>()
    val requested = mutableListOf<String>()
    val pairs = mutableListOf<ShownPair>()
    val pipeline = SubtitlePipeline(
        TextTranslator { text -> requested.add(text); translate(text) },
        object : SubtitleSender {
            override fun sendSubtitle(text: String, durationMs: Int, box: TextBox?, replaces: Boolean) {
                displays.add(Display(text, replaces, now)); events.add(text)
            }
            override fun clear() { events.add("<clear>") }
        },
        { now }, InlineExecutor(),
    ).apply { holdReadings = true; onPairShown = { pairs.add(it) } }

    /** One frame with [text] (empty: nothing on screen) at time [at]. */
    fun frame(text: String, at: Long) {
        now = maxOf(now, at)
        pipeline.process(Reading.fromText(text), CropRegion().box)
    }

    /** [text] on screen for every frame from [from] to [to] (inclusive), one frame per [step]. */
    fun show(text: String, from: Long, to: Long, step: Long = 100) {
        var at = from
        while (at <= to) { frame(text, at); at += step }
    }
}
