// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.couchsubs.mobile.core.ArabicBidi
import com.couchsubs.mobile.core.ArabicSubtitle

/** Separate RTL elements prevent a Latin label or colon from reversing the dialogue. */
@Composable
fun ArabicSubtitleText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val bodyStyle = style.copy(fontWeight = FontWeight.SemiBold, textDirection = TextDirection.Rtl,
        shadow = Shadow(Color.Black, blurRadius = 5f))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
            ArabicSubtitle.turns(text).forEach { turn ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.Center) {
                    if (turn.name != null) Text(ArabicSubtitle.isolate(turn.name + ":"),
                        style = bodyStyle.copy(fontSize = style.fontSize * 1.15f, fontWeight = FontWeight.ExtraBold),
                        color = Color(android.graphics.Color.parseColor(ArabicSubtitle.color(turn.name))),
                        modifier = Modifier.widthIn(max = 180.dp).padding(end = 8.dp), textAlign = TextAlign.Start)
                    Text(ArabicBidi.isolateLatin(turn.body), style = bodyStyle, color = Color.White,
                        textAlign = if (turn.name == null) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}
