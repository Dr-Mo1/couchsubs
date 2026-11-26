// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.settings

import com.couchsubs.mobile.core.SubtitleEvent
import com.couchsubs.mobile.core.SubtitleServer
import com.couchsubs.mobile.tv.InstalledTv
import com.couchsubs.mobile.tv.TvBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

/** Altyazı hedefi: TV, bu telefon ya da ikisi de (Mac/iPhone: SubtitleOutput.showsOnTV / showsOnComputer). */
class SubtitleTargetTest {
    @Test
    fun bothShowsOnTheTvAndOnThePhone() {
        assertTrue(SubtitleTarget.TV.showsOnTv)
        assertFalse(SubtitleTarget.TV.showsOnPhone)
        assertFalse(SubtitleTarget.PHONE.showsOnTv)
        assertTrue(SubtitleTarget.PHONE.showsOnPhone)
        assertTrue(SubtitleTarget.BOTH.showsOnTv)
        assertTrue(SubtitleTarget.BOTH.showsOnPhone)
        // "Yalnız OBS (yayın)": TV aranmaz, bu telefonda altyazı ekranı açılmaz (Mac/iPhone: SubtitleOutput.obs).
        assertFalse(SubtitleTarget.OBS.showsOnTv)
        assertFalse(SubtitleTarget.OBS.showsOnPhone)
    }

    /** Kayıtlı adlar değişmedi: eski kayıt (TV, PHONE) aynı okunur. */
    @Test
    fun storedNamesAreStable() {
        assertEquals(listOf("TV", "PHONE", "BOTH", "OBS"), SubtitleTarget.entries.map { it.name })
    }

    @Test
    fun subtitlePlaceHasExactlyTwoOptionsAndMapsTargetsCorrectly() {
        assertEquals(listOf(SubtitlePlace.PHONE_ONLY, SubtitlePlace.TV_AND_PHONE), SubtitlePlace.entries)

        assertEquals(SubtitleTarget.PHONE, SubtitlePlace.PHONE_ONLY.target)
        assertFalse(SubtitlePlace.PHONE_ONLY.showsOnTv)
        assertTrue(SubtitlePlace.PHONE_ONLY.showsOnPhone)

        assertEquals(SubtitleTarget.BOTH, SubtitlePlace.TV_AND_PHONE.target)
        assertTrue(SubtitlePlace.TV_AND_PHONE.showsOnTv)
        assertTrue(SubtitlePlace.TV_AND_PHONE.showsOnPhone)

        assertEquals(SubtitlePlace.PHONE_ONLY, SubtitlePlace.of(SubtitleTarget.PHONE))
        assertEquals(SubtitlePlace.PHONE_ONLY, SubtitlePlace.of(SubtitleTarget.OBS))
        assertEquals(SubtitlePlace.TV_AND_PHONE, SubtitlePlace.of(SubtitleTarget.TV))
        assertEquals(SubtitlePlace.TV_AND_PHONE, SubtitlePlace.of(SubtitleTarget.BOTH))
    }

    @Test
    fun tvSetupGuidanceRequiredOnlyWhenTvAndPhoneChosenWithoutConfiguredTv() {
        assertFalse(SubtitlePlace.needsTvSetup(SubtitlePlace.PHONE_ONLY, hasTvSetup = false))
        assertFalse(SubtitlePlace.needsTvSetup(SubtitlePlace.PHONE_ONLY, hasTvSetup = true))
        assertTrue(SubtitlePlace.needsTvSetup(SubtitlePlace.TV_AND_PHONE, hasTvSetup = false))
        assertFalse(SubtitlePlace.needsTvSetup(SubtitlePlace.TV_AND_PHONE, hasTvSetup = true))

        assertFalse(SubtitlePlace.hasTvConfigured(installedTv = null, tvName = null, connectedTvs = 0))
        assertFalse(SubtitlePlace.hasTvConfigured(installedTv = null, tvName = "  ", connectedTvs = 0))
        assertTrue(SubtitlePlace.hasTvConfigured(InstalledTv(TvBrand.LG, "192.168.1.30"), tvName = null, connectedTvs = 0))
        assertTrue(SubtitlePlace.hasTvConfigured(installedTv = null, tvName = "Living Room TV", connectedTvs = 0))
        assertTrue(SubtitlePlace.hasTvConfigured(installedTv = null, tvName = null, connectedTvs = 1))
    }

    @Test
    fun subtitleServerDeliversToPhoneOnlyOrBothTvAndPhone() {
        fun sendAndCheckDelivery(place: SubtitlePlace): Pair<Boolean, Boolean> {
            val server = SubtitleServer(0, clock = { 100L })
            server.sendsToTv = place.showsOnTv
            val phoneEvents = mutableListOf<SubtitleEvent>()
            if (place.showsOnPhone) {
                server.onEvent = { phoneEvents.add(it) }
            }
            val port = server.start()
            try {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 1500
                    socket.getOutputStream().write("GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    // Consume HTTP response headers and retry line.
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line == "retry: 2000") break
                    }
                    val deadline = System.currentTimeMillis() + 2000
                    while (server.connectedClients != 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)

                    server.sendSubtitle("مرحبا", 3000)
                    // Give the single-thread SSE sender a moment to flush if sending.
                    Thread.sleep(150)

                    var tvReceived = false
                    while (true) {
                        val line = try { reader.readLine() } catch (_: java.net.SocketTimeoutException) { null } ?: break
                        if (line.contains("مرحبا")) {
                            tvReceived = true
                            break
                        }
                    }
                    val phoneReceived = phoneEvents.any { it is SubtitleEvent.Subtitle && it.text == "مرحبا" }
                    return tvReceived to phoneReceived
                }
            } finally {
                server.stop()
            }
        }

        // PHONE_ONLY: phone gets it, TV does not.
        assertEquals(false to true, sendAndCheckDelivery(SubtitlePlace.PHONE_ONLY))
        // TV_AND_PHONE: both TV SSE client and phone listener receive the subtitle simultaneously.
        assertEquals(true to true, sendAndCheckDelivery(SubtitlePlace.TV_AND_PHONE))
    }
}
