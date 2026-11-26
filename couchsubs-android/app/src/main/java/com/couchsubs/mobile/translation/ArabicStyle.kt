// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import android.content.Context
import com.couchsubs.mobile.core.PackKey

/** Arabic translation style: default street Egyptian mix or clean cinema MSA. */
enum class TranslationStyle(val id: String) {
    STREET("street"),
    CLEAN("clean");

    companion object {
        val DEFAULT = STREET

        fun of(value: String?): TranslationStyle =
            entries.firstOrNull { it.id.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) }
                ?: DEFAULT

        /**
         * Cache identity of an AI engine for this style. Clean Arabic gets its own cache ("<engine>:clean") so a line cached
         * in street style (which may swear) is never served in clean style. Street, and every other language, keep the old
         * id, so nobody loses the cache they already have.
         */
        fun cacheEngineId(engine: String, target: String, style: TranslationStyle): String =
            if (target == "ar" && style == CLEAN) "$engine:clean" else engine
    }
}

/**
 * Windows AiRequest.ParseCorrections semantics: "english line = السطر العربي" per line (# comments),
 * keyed by [PackKey.canon] so case, punctuation and accents do not matter.
 */
object ArabicCorrections {
    fun parse(text: String): Map<String, String> {
        val fixes = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val at = line.indexOf(" = ")
            if (at <= 0) continue
            val english = PackKey.canon(line.substring(0, at))
            val arabic = line.substring(at + 3).trim().replace("\\n", "\n")
            if (english.isNotEmpty() && arabic.isNotEmpty()) fixes[english] = arabic
        }
        return fixes
    }
}

/** Style-specific Arabic data loaded from APK assets (or safe fallbacks when clean files are absent). */
data class ArabicStyleData(
    val style: TranslationStyle,
    val prompt: String,
    val glossary: List<Pair<String, String>>,
    val corrections: Map<String, String>,
    val packDirectory: String,
)

object ArabicStyleAssets {
    const val PROMPT_LIVE_ASSET = "arabic/arabic-system-prompt-live.txt"
    const val PROMPT_CLEAN_ASSET = "arabic/arabic-system-prompt-clean.txt"
    const val GLOSSARY_LIVE_ASSET = "arabic/glossary-ar.txt"
    const val GLOSSARY_CLEAN_ASSET = "arabic/glossary-ar-clean.txt"
    const val CORRECTIONS_LIVE_ASSET = "arabic/corrections-ar.txt"
    const val CORRECTIONS_CLEAN_ASSET = "arabic/corrections-ar-clean.txt"
    const val PACK_DIR_LIVE = "packs-ar"
    const val PACK_DIR_CLEAN = "packs-ar-clean"

    fun load(style: TranslationStyle, readAsset: (String) -> String?): ArabicStyleData = when (style) {
        TranslationStyle.STREET -> ArabicStyleData(
            style = style,
            prompt = readAsset(PROMPT_LIVE_ASSET)?.trim()?.takeIf { it.isNotEmpty() } ?: ArabicPrompt.TEXT,
            glossary = readAsset(GLOSSARY_LIVE_ASSET)?.let(ArabicGlossary::parse).orEmpty(),
            corrections = readAsset(CORRECTIONS_LIVE_ASSET)?.let(ArabicCorrections::parse).orEmpty(),
            packDirectory = PACK_DIR_LIVE,
        )
        TranslationStyle.CLEAN -> ArabicStyleData(
            style = style,
            prompt = readAsset(PROMPT_CLEAN_ASSET)?.trim()?.takeIf { it.isNotEmpty() } ?: ArabicPrompt.CLEAN_FALLBACK,
            glossary = readAsset(GLOSSARY_CLEAN_ASSET)?.let(ArabicGlossary::parse).orEmpty(),
            corrections = readAsset(CORRECTIONS_CLEAN_ASSET)?.let(ArabicCorrections::parse).orEmpty(),
            packDirectory = PACK_DIR_CLEAN,
        )
    }

    fun load(context: Context, style: TranslationStyle): ArabicStyleData = load(style) { path ->
        runCatching { context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrNull()
    }
}
