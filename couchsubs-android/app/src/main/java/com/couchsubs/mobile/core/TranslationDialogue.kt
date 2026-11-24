// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

/** Request-scoped metadata on the blocking translation worker, restored even on fallback/error. */
object TranslationDialogue {
    private val speaker = ThreadLocal<String?>()
    private val player = ThreadLocal<String?>()
    val currentSpeaker: String? get() = speaker.get()
    val currentPlayerCharacter: String? get() = player.get()
    fun <T> withSpeaker(name: String?, action: () -> T): T {
        val before = speaker.get()
        speaker.set(name)
        return try { action() } finally { if (before == null) speaker.remove() else speaker.set(before) }
    }

    fun <T> withPlayerCharacter(name: String?, action: () -> T): T {
        val before = player.get()
        player.set(PlayerCharacterInference.normalize(name))
        return try { action() } finally { if (before == null) player.remove() else player.set(before) }
    }
}
