// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.TextTranslator
import com.couchsubs.mobile.core.SubtitleSegmenter
import com.couchsubs.mobile.core.TranslationDialogue

/** Non-Turkish machine fallback, with no Turkish glossary or post-edit stage. */
class TargetLineTranslator(private val target: String, private val cache: TranslationCache,
    private val machine: (String) -> String) : TextTranslator {
    override val translationLanguage: String get() = target
    override fun translate(text: String): String {
        val key = "$target\u001f${TranslationLanguage.PROMPT_VERSION}\u001f${TranslationDialogue.currentSpeaker.orEmpty()}\u001f$text"
        return cache.get(key) ?: SubtitleSegmenter.split(text).joinToString("") { piece ->
            if (piece.translate) machine(piece.text) else piece.text
        }.also { cache.store(key, it) }
    }
}
