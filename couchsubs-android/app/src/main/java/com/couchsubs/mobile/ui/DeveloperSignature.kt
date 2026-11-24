// SPDX-License-Identifier: AGPL-3.0-only
// Developer signature once at the bottom of Settings: plain text «تطوير DrMo». DocMo
// (2026-11-24): no other developer's name or logo on any screen; the upstream notice lives on the licenses screen only.
package com.couchsubs.mobile.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.couchsubs.mobile.R

@Composable
fun DeveloperSignature() {
    Text(
        stringResource(R.string.developer_label), style = Tokens.Type.caption, color = Tokens.Color.secondary,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = Tokens.Space.screen),
    )
}
