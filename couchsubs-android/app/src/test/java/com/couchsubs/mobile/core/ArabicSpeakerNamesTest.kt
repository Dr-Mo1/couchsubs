// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArabicSpeakerNamesTest {
    @Test fun knownNamesPossessivesUnknownNamesAndAllWindowsMappings() {
        for ((name, expected) in listOf("Lucia" to "لوسيا", "JASON" to "جيسون", "Brian’S" to "برايان", "Pilot Pete's" to "الطيار بيت", "Doug" to "دوغ", "Kingfishers" to "كينغفيشرز"))
            assertEquals(expected, ArabicSpeakerNames.display(name))
        val root = File(System.getProperty("targamgi.repoRoot", ".."))
        val source = File(root, "windows/src/CouchSubs.Core/ArabicSpeakerNames.cs").readText()
        val matches = Regex("\\[\"([^\"]+)\"\\]\\s*=\\s*\"([^\"]+)\"").findAll(source).toList()
        assertEquals(121, matches.size)
        for (m in matches) assertEquals(m.groupValues[2], ArabicSpeakerNames.display(m.groupValues[1]))
    }
    @Test fun presentsArabicAndHidesOnlySourceSpeakerLabels() {
        for (input in listOf("تعالي.", "Lucia: تعالي.", "لوسيا: تعالي.")) {
            assertEquals("لوسيا: تعالي.", SpeakerPrefix.present(input, "Lucia: Come.", true, "ar"))
            assertEquals("تعالي.", SpeakerPrefix.present(input, "Lucia: Come.", false, "ar"))
        }
        assertEquals("لوسيا: تعالي.\nجيسون: حاضر.", SpeakerPrefix.present("Lucia: تعالي.\nJason: حاضر.", "Lucia: Come.\nJason: Okay.", true, "ar"))
        assertEquals("الهدف: تعال.", SpeakerPrefix.present("الهدف: تعال.", "Objective: Come.", true, "ar"))
        assertEquals("برايان: وصل.", SpeakerPrefix.present("وصل.", "Brian's: Arrived.", true, "ar"))
    }
    @Test fun colorComesFromEnglishAndSurvivesArabicAlias() {
        val book = SpeakerColorBook()
        val english = book.color("Lucia")
        val shown = SpeakerColors.presented("لوسيا: تعالي.", "Lucia: Come.", "ar", book).single()
        assertEquals("لوسيا", shown.name); assertEquals(english, shown.color)
        assertTrue(SpeakerColors.presented("تعالي.", "Lucia: Come.", "ar", book).isEmpty())
    }
    @Test fun turkishPresentationRemainsUnchanged() {
        for (show in listOf(true, false)) assertEquals(
            SpeakerPrefix.present("Lucia: Gel.", "Lucia: Come.", show),
            SpeakerPrefix.present("Lucia: Gel.", "Lucia: Come.", show, "tr"))
    }
    @Test fun serverAndPhoneShareTheEnglishColorWithAnArabicDisplayLabel() {
        SpeakerColors.session.reset()
        val englishColor = SpeakerColors.session.color("Lucia")!!
        val frame = SubtitleServer.subtitleFrame("لوسيا: تعالي.", 4000, null, 1,
            speakerColors = true, source = "Lucia: Come.", target = "ar")
        assertTrue(frame.contains("\"name\":\"لوسيا\""))
        assertTrue(frame.contains(englishColor))
        assertEquals(englishColor, SpeakerColors.segments("لوسيا: تعالي.").single().color)
        SpeakerColors.session.reset()
    }
}
