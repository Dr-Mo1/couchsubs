// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.tv

import com.couchsubs.mobile.R
import com.couchsubs.mobile.settings.SubtitleTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The display chooser's guides (C27): every target has requirements, numbered steps and 2 to 4 tips, every string exists in
 * both the Arabic (default) and the English file, and the cards never offer an install the build cannot finish.
 */
class TvGuidesTest {
    private fun strings(folder: String): Map<String, String> {
        val file = listOf("src/main/res/$folder/strings.xml", "app/src/main/res/$folder/strings.xml").map(::File).first { it.exists() }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    private val arabic = strings("values")
    private val english = strings("values-en")
    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val arabicLetters = Regex("[\\u0600-\\u06FF]")

    private val guided = DisplayChoice.entries.filter { it != DisplayChoice.UNKNOWN }

    @Test
    fun everyTargetHasRequirementsStepsAndTwoToFourTips() {
        for (choice in guided) {
            val guide = TvGuides.of(choice)!!
            assertTrue("$choice requirements", guide.requirements.isNotEmpty())
            assertTrue("$choice steps", guide.steps.size >= 3)
            assertTrue("$choice tips", guide.tips.size in 2..4)
        }
        // «لا أعرف نوع شاشتي» has its own helper, not a setup guide.
        assertNull(TvGuides.of(DisplayChoice.UNKNOWN))
    }

    @Test
    fun everyGuideStringIsInBothLocalesInTheRightLanguage() {
        for (choice in guided) {
            val guide = TvGuides.of(choice)!!
            for (id in guide.requirements + guide.steps + guide.tips) {
                val name = names.getValue(id)
                assertTrue("$name has Arabic letters", arabicLetters.containsMatchIn(arabic.getValue(name)))
                assertFalse("$name has no Arabic letters in English", arabicLetters.containsMatchIn(english.getValue(name)))
                assertTrue("$name blank", english.getValue(name).isNotBlank())
            }
        }
    }

    @Test
    fun chooserAndGuideKeysMatchInBothLocalesAndNeverHaveDirectionMarks() {
        fun ours(map: Map<String, String>) = map.filterKeys { it.startsWith("chooser_") || it.startsWith("guide_") }
        val ar = ours(arabic)
        val en = ours(english)
        assertEquals(ar.keys, en.keys)
        assertTrue(ar.size >= 60)
        for ((key, text) in ar + en) assertFalse("$key has an invisible direction mark", text.contains('\u200f') || text.contains('\u200e'))
        // Every card has a title and a note in both languages.
        for (choice in DisplayChoice.entries) {
            val title = listOf("chooser_android_title", "chooser_lg_title", "chooser_samsung_title", "chooser_browser_title", "target_phone", "chooser_unknown_title")[choice.ordinal]
            assertTrue(title, arabic.getValue(title).isNotBlank() && english.getValue(title).isNotBlank())
        }
        assertEquals("لا أعرف نوع التلفزيون", arabic.getValue("chooser_unknown_title"))
    }

    @Test
    fun samsungHostIpStepTakesThePhoneAddress() {
        assertTrue(arabic.getValue("guide_samsung_step2").contains("%1\$s"))
        assertTrue(english.getValue("guide_samsung_step2").contains("%1\$s"))
        // Only that step has a placeholder: the others are shown through the same call with the address as an ignored argument.
        for (choice in guided) for (id in TvGuides.of(choice)!!.steps) {
            val name = names.getValue(id)
            if (name != "guide_samsung_step2") assertFalse(name, arabic.getValue(name).contains("%") || english.getValue(name).contains("%"))
        }
    }

    @Test
    fun browserCardSaysThePageIsSubtitlesOnly() {
        // The page is black with text, not an overlay on the game picture; the card must say so first.
        assertTrue(arabic.getValue("chooser_browser_only").contains("لا تظهر فوق صورة اللعبة"))
        assertTrue(english.getValue("chooser_browser_only").contains("not over the game picture"))
    }

    @Test
    fun cardOpenedFirstIsTheRegisteredTvOrThePhone() {
        val lg = InstalledTv(TvBrand.LG, "192.168.1.3")
        assertEquals(DisplayChoice.LG, DisplayChoice.initial(SubtitleTarget.TV, lg))
        assertEquals(DisplayChoice.LG, DisplayChoice.initial(SubtitleTarget.BOTH, lg))
        assertEquals(DisplayChoice.PHONE, DisplayChoice.initial(SubtitleTarget.PHONE, lg))
        assertNull(DisplayChoice.initial(SubtitleTarget.TV, null))
        assertNull(DisplayChoice.initial(SubtitleTarget.OBS, lg))
        for (brand in TvBrand.entries) assertEquals(brand, DisplayChoice.of(brand).brand)
    }

    @Test
    fun installIsOfferedOnlyForPackagesThatShip() {
        val onlyLg = mapOf(TvPackages.LG_IPK to "com.couchsubs.tv_0.9.65_all.ipk")
        assertTrue(TvPackages.listed(onlyLg, TvBrand.LG))
        assertFalse(TvPackages.listed(onlyLg, TvBrand.ANDROID_TV))
        assertFalse(TvPackages.listed(emptyMap(), TvBrand.LG))
        assertTrue(TvPackages.listed(mapOf(TvPackages.ANDROID_TV_APK to "CouchSubs-androidtv.apk"), TvBrand.ANDROID_TV))
        // Samsung's .wgt is built from source on every build, so it is never missing from the index.
        assertTrue(TvPackages.listed(emptyMap(), TvBrand.SAMSUNG))
    }

    @Test
    fun secondScreenPageWorksOnOlderTvBrowsers() {
        val page = listOf("src/main/assets/second-screen.html", "app/src/main/assets/second-screen.html").map(::File).first { it.exists() }.readText()
        // `inset` (Chromium 87) and URLSearchParams (Chromium 49) would break the page on webOS 4 to 6 and older Tizen browsers.
        assertFalse(page.contains("inset: 0"))
        assertFalse(page.contains("new URLSearchParams"))
        assertTrue(page.contains("top: 0; right: 0; bottom: 0; left: 0"))
    }
}
