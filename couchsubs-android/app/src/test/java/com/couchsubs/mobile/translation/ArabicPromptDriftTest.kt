// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.translation

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArabicPromptDriftTest {
    /** Decode concatenated ordinary C# literals, ignoring // comments outside strings. */
    private fun csharp(text: String): String {
        val block = text.substringAfter("const string ArabicSystemPrompt =").substringBefore(";\n")
        val result = StringBuilder()
        var i = 0
        while (i < block.length) {
            if (block.startsWith("//", i)) { i = block.indexOf('\n', i).let { if (it < 0) block.length else it }; continue }
            if (block[i++].code != 34) continue
            while (i < block.length) {
                val c = block[i++]
                if (c.code == 34) break
                if (c.code != 92) { result.append(c); continue }
                when (val escape = block[i++]) {
                    'n' -> result.append(10.toChar()); 'r' -> result.append(13.toChar()); 't' -> result.append(9.toChar())
                    92.toChar(), 34.toChar(), 39.toChar() -> result.append(escape)
                    'u' -> { result.append(block.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> error("Unsupported C# escape: $escape")
                }
            }
        }
        return result.toString()
    }
    @Test fun promptExactlyMatchesWindowsV6AndLocalExport() {
        val root = File(System.getProperty("targamgi.repoRoot", ".."))
        val source = File(root, "windows/src/CouchSubs.Translation/AiTranslator.cs").readText().replace("\r\n", "\n")
        assertTrue(source.contains("const string ArabicSystemPrompt ="))
        val expected = csharp(source)
        assertTrue(expected.length > 7000)
        assertEquals(expected, ArabicPrompt.TEXT)
        val export = File(System.getProperty("targamgi.dataDir", "D:/Games/couchsubs-ar/build/android-data"), "arabic-system-prompt-live.txt")
        if (export.isFile) assertEquals(export.readText().trimEnd('\r', '\n'), ArabicPrompt.TEXT)
    }
}
