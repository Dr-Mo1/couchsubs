// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import java.util.Timer
import java.util.TimerTask

class InstructionDisplay(private val clock: () -> Long, private val hide: () -> Unit) {
    companion object { const val DISPLAY_MS = 4000L; const val RECENT_LIMIT = 20 }
    private val recent = ArrayDeque<String>()
    private var timer: Timer? = null
    private var until = 0L
    private var visible = false
    private var generation = 0
    @Synchronized fun version(): Int = generation
    @Synchronized fun contains(text: String) = recent.any { ControllerPrompt.same(it, text) }
    @Synchronized fun publish(text: String, version: Int, show: () -> Unit): Boolean {
        if (generation != version || contains(text)) return false
        recent.addLast(ControllerPrompt.identity(text))
        while (recent.size > RECENT_LIMIT) recent.removeFirst()
        hideVisible()
        visible = true
        until = clock() + DISPLAY_MS
        show()
        timer = Timer("Targamgi instruction expiry", true).apply {
            schedule(object : TimerTask() { override fun run() { expire() } }, DISPLAY_MS)
        }
        return true
    }
    @Synchronized fun dialogueArrived() { generation++; hideVisible() }
    @Synchronized fun expire() { if (visible && clock() >= until) hideVisible() }
    @Synchronized fun removed() { generation++; hideVisible() }
    @Synchronized fun reset() { removed(); recent.clear() }
    private fun hideVisible() {
        timer?.cancel(); timer = null
        if (visible) { visible = false; hide() }
    }
}
