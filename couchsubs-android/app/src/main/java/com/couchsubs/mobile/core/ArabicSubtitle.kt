// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

/** Display-only parsing: speaker identity and cached translation stay untouched. */
object ArabicSubtitle {
    data class Turn(val name: String?, val body: String)
    private val label = Regex("^([\\p{L}][\\p{L} .’'\\-]{0,47})\\s*[:：]\\s*(.+)$")
    private val headings = setOf("الهدف", "المهمة", "مهمة", "هدف", "ملاحظة", "تنبيه", "تحذير", "تلميح", "الوقت", "الرابط")
    fun isArabic(text: String) = text.any { it in '\u0600'..'\u06ff' }
    fun turns(text: String): List<Turn> {
        val result = mutableListOf<Turn>()
        text.replace("\u2066", "").replace("\u2067", "").replace("\u2068", "").replace("\u2069", "").lines().forEach { line ->
            val match = label.matchEntire(line.trim())
            val name = match?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
            val valid = name != null && (isArabic(name) || name.first().isUpperCase()) && name.split(' ').size <= 3 && name !in headings &&
                name.lowercase(java.util.Locale.ROOT) !in SubtitleSegmenter.notNames &&
                match.groupValues[2].let { body -> !body.startsWith('/') && body.any { it.isLetter() } }
            if (valid) result += Turn(ArabicSpeakerNames.display(name!!), match!!.groupValues[2])
            else if (result.isNotEmpty()) result[result.lastIndex] = result.last().copy(body = result.last().body + "\n" + line)
            else result += Turn(null, line)
        }
        return result
    }
    /** Every speaker name is drawn in this amber (report 12): one colour for all characters, the dialogue keeps its own. */
    const val SPEAKER_COLOR = "#FFB627"

    /** One drawn line: the speaker name on its own line above the dialogue, or the dialogue. [centered]: no speaker in this turn. */
    data class Row(val text: String, val speaker: Boolean, val centered: Boolean = false)

    fun rows(text: String): List<Row> = turns(text).flatMap { turn ->
        listOfNotNull(turn.name?.let { Row("$it:", speaker = true) }, Row(turn.body, speaker = false, centered = turn.name == null))
    }
    fun isolate(text: String) = "\u2068$text\u2069"
}
