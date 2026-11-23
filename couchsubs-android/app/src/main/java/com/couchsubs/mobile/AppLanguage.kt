// SPDX-License-Identifier: AGPL-3.0-only
// The app's language, independent of the phone's (DocMo 2026-11-23): Arabic unless English is picked in Settings, and
// an Arabic screen is Arabic only. Activities apply it as an override configuration, which survives the rotation and
// picture-in-picture changes they handle themselves; the Application and the service wrap their base context.
package com.couchsubs.mobile

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLanguage {
    const val KEY = "ui_language"
    const val ARABIC = "ar"
    const val ENGLISH = "en"

    fun get(context: Context): String =
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, ARABIC) == ENGLISH) ENGLISH else ARABIC

    /** Only the locale, and with it the layout direction. Arabic keeps Latin digits: addresses and codes read as typed. */
    fun override(context: Context) = Configuration().apply {
        setLocale(Locale.forLanguageTag(if (get(context) == ENGLISH) "en" else "ar-u-nu-latn"))
    }

    fun wrap(base: Context): Context = base.createConfigurationContext(override(base))
}
