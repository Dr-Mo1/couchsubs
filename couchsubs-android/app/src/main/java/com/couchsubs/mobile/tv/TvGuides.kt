// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.tv

import com.couchsubs.mobile.R
import com.couchsubs.mobile.settings.SubtitleTarget

/** The cards of the display chooser, in screen order. [brand] is set for the three TV apps. */
enum class DisplayChoice(val brand: TvBrand?) {
    ANDROID_TV(TvBrand.ANDROID_TV), LG(TvBrand.LG), SAMSUNG(TvBrand.SAMSUNG), BROWSER(null), PHONE(null), UNKNOWN(null);

    companion object {
        fun of(brand: TvBrand): DisplayChoice = entries.first { it.brand == brand }

        /** The card that starts open: the registered TV when subtitles go to a TV, the phone screen for "phone only". */
        fun initial(target: SubtitleTarget, tv: InstalledTv?): DisplayChoice? = when {
            target.showsOnTv && tv != null -> of(tv.brand)
            target == SubtitleTarget.PHONE -> PHONE
            else -> null
        }
    }
}

/** One setup guide, as string resource ids: what it needs, the numbered steps, and 2 to 4 tips for when it does not work. */
data class TvGuide(val requirements: List<Int>, val steps: List<Int>, val tips: List<Int>)

object TvGuides {
    private fun ids(vararg resources: Int) = resources.toList()

    /** The guide of [choice]; null for «لا أعرف نوع شاشتي», which has its own helper rather than a setup guide. */
    fun of(choice: DisplayChoice): TvGuide? = when (choice) {
        DisplayChoice.ANDROID_TV -> TvGuide(
            ids(R.string.guide_android_req1, R.string.guide_android_req2, R.string.guide_android_req3, R.string.guide_android_req4),
            ids(R.string.guide_android_step1, R.string.guide_android_step2, R.string.guide_android_step3, R.string.guide_android_step4,
                R.string.guide_android_step5, R.string.guide_android_step6),
            ids(R.string.guide_android_tip1, R.string.guide_android_tip2, R.string.guide_android_tip3, R.string.guide_android_tip4))
        DisplayChoice.LG -> TvGuide(
            ids(R.string.guide_lg_req1, R.string.guide_lg_req2, R.string.guide_lg_req3),
            ids(R.string.guide_lg_step1, R.string.guide_lg_step2, R.string.guide_lg_step3, R.string.guide_lg_step4, R.string.guide_lg_step5),
            ids(R.string.guide_lg_tip1, R.string.guide_lg_tip2, R.string.guide_lg_tip3, R.string.guide_lg_tip4))
        DisplayChoice.SAMSUNG -> TvGuide(
            ids(R.string.guide_samsung_req1, R.string.guide_samsung_req2, R.string.guide_samsung_req3),
            ids(R.string.guide_samsung_step1, R.string.guide_samsung_step2, R.string.guide_samsung_step3, R.string.guide_samsung_step4,
                R.string.guide_samsung_step5, R.string.guide_samsung_step6),
            ids(R.string.guide_samsung_tip1, R.string.guide_samsung_tip2, R.string.guide_samsung_tip3, R.string.guide_samsung_tip4))
        DisplayChoice.BROWSER -> TvGuide(
            ids(R.string.guide_browser_req1, R.string.guide_browser_req2, R.string.guide_browser_req3),
            ids(R.string.guide_browser_step1, R.string.guide_browser_step2, R.string.guide_browser_step3, R.string.guide_browser_step4),
            ids(R.string.guide_browser_tip1, R.string.guide_browser_tip2, R.string.guide_browser_tip3))
        DisplayChoice.PHONE -> TvGuide(
            ids(R.string.guide_phone_req1, R.string.guide_phone_req2),
            ids(R.string.guide_phone_step1, R.string.guide_phone_step2, R.string.guide_phone_step3),
            ids(R.string.guide_phone_tip1, R.string.guide_phone_tip2, R.string.guide_phone_tip3))
        DisplayChoice.UNKNOWN -> null
    }
}
