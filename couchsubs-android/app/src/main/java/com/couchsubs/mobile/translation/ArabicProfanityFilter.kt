// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.TextTranslator

/**
 * Profanity filter applied to FREE (ML Kit) Arabic output when clean MSA style is selected.
 * Matches whole crude Arabic words and phrases (Egyptian and MSA) after normalizing diacritics,
 * alef/hamza, ta marbuta, and alef maqsura, replacing them with mild MSA equivalents or dropping them.
 */
object ArabicProfanityFilter {
    private val diacritics = Regex("[\u064B-\u065F\u0670\u0640]")
    private val arabicLetter = Regex("[\u0621-\u064A\u0671]")
    private val multiSpace = Regex("[ \\t]{2,}")
    private val spaceBeforePunct = Regex(" ([،؟؛!.,:])")
    private val leadingPunctSpace = Regex("([،؟؛!.])(?=\\S)")

    private fun norm(text: String): String {
        val stripped = diacritics.replace(text, "")
        val out = StringBuilder(stripped.length)
        for (c in stripped) {
            out.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ة' -> 'ه'
                    'ى' -> 'ي'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    else -> c
                },
            )
        }
        return out.toString()
    }

    /** Multi-word crude phrases checked first (normalized tokens -> mild replacement, or "" to drop). */
    private val phrases: List<Pair<List<String>, String>> = listOf(
        "يا ابن الشرموطه" to "أيها الوغد",
        "يا ابن المتناكه" to "أيها الوغد",
        "يا ابن العاهره" to "أيها الوغد",
        "يا ابن القحبه" to "أيها الوغد",
        "يا ابن الزانيه" to "أيها الوغد",
        "يا ابن الوسخه" to "أيها الوغد",
        "يا ابن الكلب" to "أيها الوغد",
        "يا بنت الشرموطه" to "أيتها الوغدة",
        "يا بنت المتناكه" to "أيتها الوغدة",
        "يا بنت العاهره" to "أيتها الوغدة",
        "يا بنت القحبه" to "أيتها الوغدة",
        "يا بنت الوسخه" to "أيتها الوغدة",
        "يا بنت الكلب" to "أيتها الوغدة",
        "ابن الشرموطه" to "الوغد",
        "ابن المتناكه" to "الوغد",
        "ابن العاهره" to "الوغد",
        "ابن القحبه" to "الوغد",
        "ابن الزانيه" to "الوغد",
        "ابن الوسخه" to "الوغد",
        "ابن الحرام" to "الوغد",
        "ابن الكلب" to "الوغد",
        "ابن كلب" to "وغد",
        "بنت الشرموطه" to "الوغدة",
        "بنت المتناكه" to "الوغدة",
        "بنت العاهره" to "الوغدة",
        "بنت القحبه" to "الوغدة",
        "بنت الزانيه" to "الوغدة",
        "بنت الوسخه" to "الوغدة",
        "بنت الحرام" to "الوغدة",
        "بنت الكلب" to "الوغدة",
        "بنت كلب" to "وغدة",
        "كس امك" to "تبًا",
        "كس امه" to "تبًا",
        "كس امها" to "تبًا",
        "يلعن دين" to "تبًا",
        "يلعن ابوك" to "تبًا",
        "يلعن امك" to "تبًا",
        "يخرب بيتك" to "تبًا",
        "يخرب بيت" to "تبًا",
        "يا علق" to "أيها الوغد",
        "يا كلب" to "أيها الوغد",
        "يا وسخ" to "أيها الحقير",
        "يا وسخه" to "أيتها الحقيرة",
        "يا زباله" to "أيها الحقير",
        "يا زفت" to "أيها الأحمق",
        "يا بهيمه" to "أيها الأحمق",
        "عضو ذكري" to "",
        "ممارسه الجنس" to "",
    ).map { (phrase, replacement) -> norm(phrase).split(' ') to replacement }
        .sortedByDescending { it.first.size }

    /** Single crude words (Egyptian & MSA), normalized -> mild replacement ("" drops the word). */
    private val words: Map<String, String> = mapOf(
        // Exclamations
        "احا" to "تبًا",
        "احيه" to "تبًا",
        "خرا" to "تبًا",
        "خراء" to "تبًا",
        "براز" to "قرف",
        "غائط" to "قرف",
        // Crude Egyptian & MSA insults (masculine)
        "معرص" to "وغد",
        "معرصين" to "أوغاد",
        "عرص" to "وغد",
        "عراصين" to "أوغاد",
        "متناك" to "وغد",
        "متناكين" to "أوغاد",
        "منيوك" to "وغد",
        "منيك" to "وغد",
        "خول" to "جبان",
        "خولات" to "جبناء",
        "شرموط" to "وغد",
        "ديوث" to "وغد",
        "سافل" to "حقير",
        "منحط" to "حقير",
        "علوق" to "أوغاد",
        // Crude Egyptian & MSA insults (feminine)
        "شرموطه" to "وغدة",
        "شراميط" to "وغدات",
        "متناكه" to "وغدة",
        "معرصه" to "وغدة",
        "قحبه" to "وغدة",
        "قحاب" to "وغدات",
        "عاهره" to "وغدة",
        "عاهرات" to "وغدات",
        "زانيه" to "وغدة",
        "مومس" to "وغدة",
        "فاجره" to "وغدة",
        "لبوه" to "وغدة",
        "سافله" to "حقيرة",
        // Explicit sexual / anatomical terms
        "نيك" to "",
        "ينيك" to "",
        "تنيك" to "",
        "انيك" to "",
        "انيكك" to "",
        "منيكه" to "",
        "بتتناك" to "",
        "يتناك" to "",
        "اتناك" to "",
        "مضاجعه" to "",
        "يضاجع" to "",
        "تضاجع" to "",
        "زب" to "",
        "زبي" to "",
        "زبك" to "",
        "زبه" to "",
        "زبر" to "",
        "زبري" to "",
        "زبرك" to "",
        "كس" to "",
        "كسم" to "تبًا",
        "كسمك" to "تبًا",
        "كسها" to "",
        "طيز" to "",
        "طيزي" to "",
        "طيزك" to "",
        "طيزه" to "",
        "طيزها" to "",
        "بضان" to "مزعج",
        "بضاني" to "مزعج",
    ).mapKeys { norm(it.key) }

    /** Common Arabic proclitics attached to a base word without space. */
    private val prefixes = listOf("وال", "بال", "فال", "كال", "لل", "ال", "و", "ب", "ف", "ل", "ك")

    private data class WordSpan(val raw: String, val coreStart: Int, val coreEnd: Int) {
        val leading: String get() = raw.substring(0, coreStart)
        val core: String get() = raw.substring(coreStart, coreEnd)
        val trailing: String get() = raw.substring(coreEnd)
        val normalized: String get() = norm(core)
    }

    private fun splitSpan(token: String): WordSpan {
        var start = 0
        while (start < token.length && !arabicLetter.matches(token[start].toString())) start++
        var end = token.length
        while (end > start && !arabicLetter.matches(token[end - 1].toString())) end--
        return WordSpan(token, start, end)
    }

    private fun matchWord(normalizedWord: String): Pair<String, String>? {
        if (normalizedWord.isEmpty()) return null
        words[normalizedWord]?.let { return "" to it }
        for (prefix in prefixes) {
            if (normalizedWord.length > prefix.length + 1 && normalizedWord.startsWith(prefix)) {
                val stem = normalizedWord.substring(prefix.length)
                // Avoid stripping single-letter proclitics onto very short 2-letter roots (e.g. وكس / بزب still fine,
                // but guard against accidental matches by requiring stem >= 3 for single-letter prefixes except "ال").
                if (prefix.length == 1 && stem.length < 3) continue
                val replacement = words[stem] ?: continue
                val keptPrefix = if (replacement.isEmpty()) "" else when (prefix) {
                    "وال" -> "وال"
                    "بال" -> "بال"
                    "فال" -> "فال"
                    "كال" -> "كال"
                    "لل" -> "لل"
                    "ال" -> "ال"
                    else -> prefix
                }
                // Avoid awkward "ال" + exclamation like "التبًا"
                val finalPrefix = if (replacement == "تبًا" || replacement.startsWith("أيه") || replacement.startsWith("أيته")) {
                    if (prefix.startsWith("و")) "و" else ""
                } else keptPrefix
                return finalPrefix to replacement
            }
        }
        return null
    }

    /** Clean a single line (or multi-line subtitle) while preserving line breaks and speaker labels. */
    fun clean(text: String): String {
        if (text.isBlank()) return text
        return text.split('\n').joinToString("\n") { cleanLine(it) }
    }

    private fun cleanLine(line: String): String {
        if (line.isBlank()) return line
        val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val spans = tokens.map(::splitSpan)
        val out = mutableListOf<String>()
        var i = 0
        while (i < spans.size) {
            var matchedPhrase = false
            for ((phrase, replacement) in phrases) {
                if (i + phrase.size <= spans.size && matchesPhrase(spans, i, phrase)) {
                    val lead = spans[i].leading
                    val trail = spans[i + phrase.size - 1].trailing
                    val piece = lead + replacement + trail
                    if (piece.isNotEmpty()) out.add(piece)
                    i += phrase.size
                    matchedPhrase = true
                    break
                }
            }
            if (matchedPhrase) continue
            val span = spans[i]
            val match = matchWord(span.normalized)
            if (match != null) {
                val (prefix, replacement) = match
                val piece = span.leading + prefix + replacement + span.trailing
                if (piece.isNotEmpty()) out.add(piece)
            } else {
                out.add(span.raw)
            }
            i++
        }
        val joined = out.joinToString(" ")
            .replace(multiSpace, " ")
            .replace(spaceBeforePunct, "$1")
            .trim()
        return joined
    }

    private fun matchesPhrase(spans: List<WordSpan>, start: Int, phrase: List<String>): Boolean {
        for (j in phrase.indices) {
            val word = spans[start + j].normalized
            val expected = phrase[j]
            if (j == 0 && word == "و$expected") continue
            if (word != expected) return false
            if (j < phrase.size - 1 && spans[start + j].trailing.isNotEmpty()) return false
        }
        return true
    }
}

/** Wraps a [TextTranslator] (such as FREE ML Kit) to filter crude Arabic words in clean style. */
class CleanArabicTranslator(private val delegate: TextTranslator) : TextTranslator {
    override val translationLanguage: String get() = delegate.translationLanguage
    override val translatesTogether: Boolean get() = delegate.translatesTogether
    override fun noteSpeaker(name: String) = delegate.noteSpeaker(name)
    override fun translate(text: String): String = ArabicProfanityFilter.clean(delegate.translate(text))
    override fun translateAll(texts: List<String>): List<String> =
        delegate.translateAll(texts).map(ArabicProfanityFilter::clean)
}
