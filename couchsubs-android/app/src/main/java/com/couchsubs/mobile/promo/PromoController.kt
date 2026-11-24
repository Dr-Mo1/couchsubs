// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.promo

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Targamgi does not request or display upstream promotions. */
object PromoController {
    data class Ready(val popup: PromoPopup, val image: Bitmap)
    private val state = MutableStateFlow<Ready?>(null)
    val ready: StateFlow<Ready?> = state

    fun refresh(context: Context, scope: CoroutineScope, setupDone: Boolean) { state.value = null }
    fun dismissed(context: Context, popup: PromoPopup) { state.value = null }
}
