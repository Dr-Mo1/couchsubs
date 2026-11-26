// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

/** Check actual Android resources, not a second list of translated copy. */
class UiLanguageTest {
    private val root = File(System.getProperty("targamgi.repoRoot", ".."))
    private val main = File(root, "couchsubs-android/app/src/main")
    private val placeholder = Regex("%([0-9]+\\$)?[ds]")
    private val address = Regex("(?:https?://[^\\s]+|(?:[a-zA-Z0-9-]+\\.)+(?:com|net|org|dev)(?:/[^\\s]*)?)")

    private fun strings(locale: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val nodes = factory.newDocumentBuilder().parse(File(main, "res/$locale/strings.xml")).getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name").nodeValue
            assertFalse("Duplicate resource: $locale/$name", result.containsKey(name))
            result[name] = node.textContent
        }
        return result
    }

    @Test fun arabicUiHasNoLatinLabels() {
        // "SubTV" is the product name (1.0.48); subscribe_contact is the owner's contact (a handle or number, so Latin).
        val offenders = strings("values").filterKeys { it != "subscribe_contact" }.filterValues { value ->
            val copy = address.replace(placeholder.replace(value, ""), "").replace("DrMo", "").replace("SubTV", "")
            copy.any { it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
        }.keys
        assertTrue("Latin letters in Arabic resources: ${offenders.joinToString()}", offenders.isEmpty())
    }

    @Test fun englishUiHasNoArabicLabels() {
        val english = strings("values-en")
        val fallbacks = strings("values").filterKeys { it !in english && it != "launcher_label" }
        val offenders = (fallbacks + english).filterValues { value ->
            value.any { it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC }
        }.keys
        assertTrue("Arabic letters in English resources: ${offenders.joinToString()}", offenders.isEmpty())
    }

    @Test fun translatedResourcesKeepTheSamePlaceholders() {
        val arabic = strings("values")
        val english = strings("values-en")
        val offenders = english.keys.filter { name ->
            name !in arabic || placeholder.findAll(arabic.getValue(name)).map { it.value }.toList().sorted() !=
                placeholder.findAll(english.getValue(name)).map { it.value }.toList().sorted()
        }
        assertTrue("Missing resource or changed placeholders: ${offenders.joinToString()}", offenders.isEmpty())
    }

    @Test fun launcherKeepsItsOwnLabel() {
        // 1.0.48: the visible name is SubTV in both languages; the launcher label stays a single resource in the default locale.
        assertEquals("SubTV", strings("values")["launcher_label"])
        assertFalse(strings("values-en").containsKey("launcher_label"))
        assertEquals("SubTV", strings("values")["app_name"])
        assertEquals("SubTV", strings("values-en")["app_name"])
        assertTrue(File(main, "AndroidManifest.xml").readText().contains("android:label=\"@string/launcher_label\""))
    }
}
