// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import java.util.Locale

/** Recent protagonist labels, independent of the current speaker (Windows PlayerCharacterInference). */
class PlayerCharacterInference(private val clock: () -> Long) {
    companion object {
        const val WINDOW_MS = 15 * 60 * 1000L
        const val MINIMUM_LABELS = 3

        fun normalize(name: String?): String? = when (name?.trim()?.lowercase(Locale.ROOT)) {
            "jason" -> "Jason"
            "lucia" -> "Lucia"
            else -> null
        }
    }

    private val labels = ArrayDeque<Pair<String, Long>>()

    @Synchronized
    fun observe(label: String?) {
        val name = normalize(label) ?: return
        labels.addLast(name to clock())
        while (labels.size > 10) labels.removeFirst()
    }

    @Synchronized
    fun resolve(selection: String? = "auto"): String? {
        normalize(selection)?.let { return it }
        val cutoff = clock() - WINDOW_MS
        while (labels.firstOrNull()?.let { it.second < cutoff } == true) labels.removeFirst()
        if (labels.size < MINIMUM_LABELS) return null
        val jason = labels.count { it.first == "Jason" }
        if (jason * 10 >= labels.size * 7) return "Jason"
        return if ((labels.size - jason) * 10 >= labels.size * 7) "Lucia" else null
    }
}
