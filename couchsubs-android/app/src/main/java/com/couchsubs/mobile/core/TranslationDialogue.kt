// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

/** Request-scoped metadata on the blocking translation worker, restored even on fallback/error. */
object TranslationDialogue {
    private val speaker = ThreadLocal<String?>()
    val currentSpeaker: String? get() = speaker.get()
    fun <T> withSpeaker(name: String?, action: () -> T): T {
        val before = speaker.get()
        speaker.set(name)
        return try { action() } finally { if (before == null) speaker.remove() else speaker.set(before) }
    }
}
