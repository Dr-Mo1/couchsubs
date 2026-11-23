// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

object ArabicBidi {
    fun isolateLatin(text: String): String = if (text.none { it in '\u0600'..'\u06ff' }) text
        else Regex("[A-Za-z][A-Za-z0-9'’ .-]*").replace(text) { "\u2068${it.value.trimEnd()}\u2069" + it.value.takeLastWhile { c -> c == ' ' } }
}
