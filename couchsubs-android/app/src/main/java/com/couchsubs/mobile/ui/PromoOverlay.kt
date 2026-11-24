// SPDX-License-Identifier: AGPL-3.0-only
// Upstream promotional UI is disabled in Targamgi.
package com.couchsubs.mobile.ui

import androidx.compose.runtime.Composable
import com.couchsubs.mobile.promo.PromoController

/** Promotions are disabled for this app, including any already prepared popup. */
@Composable
fun PromoOverlay(ready: PromoController.Ready, onDismiss: () -> Unit) = Unit
