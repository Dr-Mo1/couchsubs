// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

/** Same controller-hint identity and safe speech exceptions as Windows. */
object ControllerPrompt {
    private val buttons = Regex("""\b(?:[LR][123]|Triangle|Circle|Square|Cross|Options|Touchpad)\b|[△▲○◯□▢✕×✖\uFFFC]""", RegexOption.IGNORE_CASE)
    private val verbs = Regex("""\b(?:Hold|Press|Tap|Use|Toggle|Move)\b""", RegexOption.IGNORE_CASE)
    fun isPrompt(text: String): Boolean {
        if (SpeakerPrefix.split(text) != null) return false
        val verb = verbs.find(text) ?: return false
        if (buttons.findAll(text).any { button ->
                val start = minOf(verb.range.last + 1, button.range.last + 1)
                val end = maxOf(verb.range.first, button.range.first)
                TextMatch.normalize(text.substring(start, end)).split(' ').count { it.isNotEmpty() } <= 2 }) return true
        if (verb.range.first != text.length - text.trimStart().length) return false
        return !SubtitleStabilizer.endsSentence(text) &&
            !Regex("""^hold\s+(?:the|a|an|my|your|his|her|our|their|on|up|it|me|us)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text.trimStart())
    }
    fun identity(text: String): String {
        val normalized = TextMatch.normalize(buttons.replace(text, " "))
        return if (isPrompt(text)) Regex("""\b\d\b""").replace(normalized, " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ") else normalized
    }
    fun same(a: String, b: String): Boolean {
        val x = identity(a); val y = identity(b)
        return !TextMatch.addsMeaning(x, y) && !TextMatch.addsMeaning(y, x) && TextMatch.isSimilar(x, y, 0.9)
    }
}
