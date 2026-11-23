// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import com.couchsubs.mobile.core.TextTranslator
import com.couchsubs.mobile.core.PackMatch

class PackFirstTranslator(private val engine: TextTranslator, private val lookup: (String) -> PackMatch?) : TextTranslator {
    override val translationLanguage: String get() = engine.translationLanguage
    override val translatesTogether: Boolean get() = engine.translatesTogether
    override fun noteSpeaker(name: String) = engine.noteSpeaker(name)
    override fun translate(text: String): String = lookup(text)?.text ?: engine.translate(text)
    override fun translateAll(texts: List<String>): List<String> {
        val results = texts.map { lookup(it)?.text }.toMutableList()
        val missing = texts.indices.filter { results[it] == null }
        if (missing.isNotEmpty()) {
            val translated = engine.translateAll(missing.map { texts[it] })
            require(translated.size == missing.size)
            missing.zip(translated).forEach { (index, translation) -> results[index] = translation }
        }
        return results.map { requireNotNull(it) }
    }
}
