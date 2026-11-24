// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Arka planda sürdür (Resim içinde resim)": pencere yalnız çeviri sürerken ve ayar açıkken açılır; iki etkinlik de PiP
 * destekler; ayar açıklaması iki dilde de oyun görüntüsünü ve çeviriyi anlatır.
 */
class BackgroundPipTest {
    private fun file(vararg candidates: String): File =
        candidates.flatMap { listOf(it, "couchsubs-android/$it", "../$it") }.map(::File).first { it.exists() }

    @Test
    fun windowOpensOnlyWhileTranslatingWithSettingOn() {
        assertTrue(BackgroundPip.eligible(running = true, enabled = true, supported = true))
        assertFalse(BackgroundPip.eligible(running = false, enabled = true, supported = true))
        assertFalse(BackgroundPip.eligible(running = true, enabled = false, supported = true))
        assertFalse(BackgroundPip.eligible(running = true, enabled = true, supported = false))
    }

    @Test
    fun bothActivitiesSupportPictureInPicture() {
        val manifest = file("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml").readText()
        assertEquals(2, Regex("android:supportsPictureInPicture=\"true\"").findAll(manifest).count())
        // Çeviri hizmeti ağdaki konsol ve TV ile konuşur: Android 14+ türü connectedDevice (Play beyanı bununla yapıldı).
        assertTrue(manifest.contains("android:foregroundServiceType=\"connectedDevice\""))
    }

    @Test
    fun settingExplainsFloatingPictureAndTranslationInBothLanguages() {
        // C28 gives this fork its own copy; validate the explanation rather than upstream iPhone wording.
        for ((folder, phrases) in listOf(
            "values" to listOf("صورة اللعبة", "الترجمة", "نافذة صغيرة", "مغادرة التطبيق"),
            "values-en" to listOf("game picture", "live subtitle", "small window", "leave the translation app"),
        )) {
            val resource = file("app/src/main/res/$folder/strings.xml", "src/main/res/$folder/strings.xml")
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource).getElementsByTagName("string")
            val strings = (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            assertTrue("$folder title", strings.getValue("settings_background_pip").isNotBlank())
            val description = strings.getValue("settings_background_pip_short")
            for (phrase in phrases) assertTrue("$folder: $phrase missing from $description", description.contains(phrase))
        }
    }
}
