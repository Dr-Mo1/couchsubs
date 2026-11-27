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
    fun fallbackUrlRescuesBlockedPrimaryAcrossStableAndBeta() {
        val githubStable = "https://github.com/Dr-Mo1/targamgi/releases/download/targamgi-latest/update.json"
        val githubBeta = "https://github.com/Dr-Mo1/targamgi/releases/download/targamgi-latest/update-beta.json"
        val workerStable = "https://subtv-dl.dr-mohamedhamdy2020.workers.dev/update.json"
        val workerBeta = "https://subtv-dl.dr-mohamedhamdy2020.workers.dev/update-beta.json"
        val json47 = """{"versionCode":47,"versionName":"1.0.47","apkUrl":"https://subtv-dl.dr-mohamedhamdy2020.workers.dev/targamgi-v47/Targamgi-1.0.47.apk","sha256":"${"a".repeat(64)}","sizeBytes":1000}"""

        // GitHub succeeds -> Worker is never contacted.
        val primarySeen = mutableListOf<String>()
        val ok = UpdateChannel.fetchInfo(githubStable, workerStable, UpdateChannel.STABLE) { url ->
            primarySeen += url
            json47
        }
        assertEquals(47, ok?.versionCode)
        assertEquals(listOf(githubStable), primarySeen)

        // GitHub blocked (network error) -> Worker fallback succeeds.
        val stableFallbackSeen = mutableListOf<String>()
        val rescuedStable = UpdateChannel.fetchInfo(githubStable, workerStable, UpdateChannel.STABLE) { url ->
            stableFallbackSeen += url
            if (url == githubStable) throw UpdateException(UpdateFailureKind.NETWORK, "timeout")
            json47
        }
        assertEquals(47, rescuedStable?.versionCode)
        assertEquals(listOf(githubStable, workerStable), stableFallbackSeen)

        // Beta channel: GitHub blocked -> Worker tries update-beta.json, gets 404, falls back to Worker update.json.
        val betaFallbackSeen = mutableListOf<String>()
        val rescuedBeta = UpdateChannel.fetchInfo(githubStable, workerStable, UpdateChannel.BETA) { url ->
            betaFallbackSeen += url
            when (url) {
                githubBeta -> throw UpdateException(UpdateFailureKind.NETWORK, "connection reset")
                workerBeta -> throw UpdateException(UpdateFailureKind.NETWORK, "HTTP 404", httpStatus = 404)
                workerStable -> json47
                else -> error("unexpected url $url")
            }
        }
        assertEquals(47, rescuedBeta?.versionCode)
        assertEquals(listOf(githubBeta, workerBeta, workerStable), betaFallbackSeen)

        // Both fail with network errors -> UpdateException propagates.
        assertThrows(UpdateException::class.java) {
            UpdateChannel.fetchInfo(githubStable, workerStable, UpdateChannel.STABLE) {
                throw UpdateException(UpdateFailureKind.NETWORK, "offline")
            }
        }

        // Both return invalid manifests -> returns null.
        assertNull(UpdateChannel.fetchInfo(githubStable, workerStable, UpdateChannel.STABLE) { "not json" })
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
