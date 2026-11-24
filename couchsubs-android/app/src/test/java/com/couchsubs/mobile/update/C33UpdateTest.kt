// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.update

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class C33UpdateTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("c33-update-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun channelUrlSelectionAnd404Fallback() {
        val stable = "https://example.com/releases/download/targamgi-latest/update.json"
        val beta = "https://example.com/releases/download/targamgi-latest/update-beta.json"
        assertEquals(stable, UpdateChannel.manifestUrl(stable, "stable"))
        assertEquals(stable, UpdateChannel.manifestUrl(stable, "unknown"))
        assertEquals(beta, UpdateChannel.manifestUrl(stable, "beta"))

        // Stable fetches update.json directly.
        val stableSeen = mutableListOf<String>()
        assertEquals("stable-body", UpdateChannel.fetchManifest(stable, "stable") { url ->
            stableSeen += url
            "stable-body"
        })
        assertEquals(listOf(stable), stableSeen)

        // Beta returns update-beta.json when present.
        val betaSeen = mutableListOf<String>()
        assertEquals("beta-body", UpdateChannel.fetchManifest(stable, "beta") { url ->
            betaSeen += url
            "beta-body"
        })
        assertEquals(listOf(beta), betaSeen)

        // Beta falls back to update.json on HTTP 404.
        val fallbackSeen = mutableListOf<String>()
        val result = UpdateChannel.fetchManifest(stable, "beta") { url ->
            fallbackSeen += url
            if (url == beta) throw UpdateException(UpdateFailureKind.NETWORK, "HTTP 404", httpStatus = 404)
            "stable-fallback"
        }
        assertEquals("stable-fallback", result)
        assertEquals(listOf(beta, stable), fallbackSeen)

        // Non-404 errors (e.g. 500) do not fall back silently.
        assertThrows(UpdateException::class.java) {
            UpdateChannel.fetchManifest(stable, "beta") { url ->
                if (url == beta) throw UpdateException(UpdateFailureKind.NETWORK, "HTTP 500", httpStatus = 500)
                "should-not-reach"
            }
        }
    }

    @Test
    fun sevenTapToggleSwitchesChannelAndResetsCounter() {
        var count = 0
        var channel = UpdateChannel.STABLE
        repeat(6) { index ->
            val r = BetaChannelToggle.tap(count, channel)
            assertFalse(r.toggled)
            assertEquals(index + 1, r.count)
            assertEquals(UpdateChannel.STABLE, r.channel)
            count = r.count
            channel = r.channel
        }
        val on = BetaChannelToggle.tap(count, channel)
        assertTrue(on.toggled)
        assertEquals(0, on.count)
        assertEquals(UpdateChannel.BETA, on.channel)

        count = on.count
        channel = on.channel
        repeat(6) {
            val r = BetaChannelToggle.tap(count, channel)
            assertFalse(r.toggled)
            count = r.count
            channel = r.channel
        }
        val off = BetaChannelToggle.tap(count, channel)
        assertTrue(off.toggled)
        assertEquals(0, off.count)
        assertEquals(UpdateChannel.STABLE, off.channel)
    }

    @Test
    fun rescueMarkerCountsUnfinishedStartsPerVersionCode() {
        // First two unfinished starts for version 50 do not trigger rescue yet.
        assertFalse(RescueMarker.onAppStart(dir, 50))
        assertEquals(RescueMarker.State(50, 1), RescueMarker.read(dir))
        assertFalse(RescueMarker.onAppStart(dir, 50))
        assertEquals(RescueMarker.State(50, 2), RescueMarker.read(dir))

        // Third launch after 2 unfinished starts enters rescue mode.
        assertTrue(RescueMarker.onAppStart(dir, 50))
        assertEquals(RescueMarker.State(50, 2), RescueMarker.read(dir))

        // Upgrading to a new versionCode resets the counter and launches normally.
        assertFalse(RescueMarker.onAppStart(dir, 51))
        assertEquals(RescueMarker.State(51, 1), RescueMarker.read(dir))

        // Clearing after first frame removes the marker so subsequent launches start from 0.
        RescueMarker.clear(dir)
        assertNull(RescueMarker.read(dir))
        assertFalse(RescueMarker.onAppStart(dir, 51))
        assertEquals(RescueMarker.State(51, 1), RescueMarker.read(dir))
    }
}
