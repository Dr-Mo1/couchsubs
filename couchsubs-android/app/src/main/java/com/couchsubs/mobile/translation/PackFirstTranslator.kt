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

/**
 * 1.0.48 part B: enforces "no subscription = no translation at all" in front of the entire translation chain
 * (including local pack, corrections, cache, free engine and online engines).
 */
class AccessGatedTranslator(
    private val delegate: TextTranslator,
    private val isAllowed: () -> Boolean,
    private val onBlocked: () -> Unit = {},
) : TextTranslator {
    override val translationLanguage: String get() = delegate.translationLanguage
    override val translatesTogether: Boolean get() = delegate.translatesTogether

    override fun noteSpeaker(name: String) {
        if (isAllowed()) delegate.noteSpeaker(name)
    }

    override fun translate(text: String): String {
        if (!isAllowed()) {
            onBlocked()
            throw GateException(GateError.ACCESS_ENDED, "access_inactive")
        }
        return delegate.translate(text)
    }

    override fun translateAll(texts: List<String>): List<String> {
        if (!isAllowed()) {
            onBlocked()
            throw GateException(GateError.ACCESS_ENDED, "access_inactive")
        }
        return delegate.translateAll(texts)
    }
}
