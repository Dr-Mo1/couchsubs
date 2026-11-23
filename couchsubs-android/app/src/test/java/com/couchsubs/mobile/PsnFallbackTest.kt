// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile

import com.couchsubs.mobile.remoteplay.PsnFallback
import com.couchsubs.mobile.remoteplay.PsnPageProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PsnFallbackTest {
    private val webView = "Mozilla/5.0 (Linux; Android 16; 23049RAD8C Build/BP2A.250605.031.A3; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Version/4.0 Chrome/131.0.6778.260 Mobile Safari/537.36"
    private val chrome = "Mozilla/5.0 (Linux; Android 16; 23049RAD8C Build/BP2A.250605.031.A3) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.6778.260 Mobile Safari/537.36"

    @Test
    fun webViewUserAgentLooksLikeChromeMobile() {
        assertEquals(chrome, PsnFallback.chromeLikeUserAgent(webView))
    }

    @Test
    fun aChromeUserAgentIsLeftAlone() {
        assertEquals(chrome, PsnFallback.chromeLikeUserAgent(chrome))
        assertEquals(chrome, PsnFallback.chromeLikeUserAgent(PsnFallback.chromeLikeUserAgent(webView)))
    }

    @Test
    fun otherTokensStay() {
        assertEquals("Mozilla/5.0 (Linux; Android 9; SM-J530F Build/PPR1) AppleWebKit/537.36 Chrome/90.0 Mobile Safari/537.36",
            PsnFallback.chromeLikeUserAgent("Mozilla/5.0 (Linux; Android 9; SM-J530F Build/PPR1; wv) AppleWebKit/537.36 Version/4.0 Chrome/90.0 Mobile Safari/537.36"))
    }

    @Test
    fun browserIsMainAfterAFailureAndAfterAnEarlierOne() {
        assertFalse(PsnFallback.browserFirst(problem = null, failedBefore = false)) // fresh phone: the in-app page first
        assertTrue(PsnFallback.browserFirst(problem = null, failedBefore = true)) // one failed attempt earlier on this phone
        for (problem in listOf(PsnPageProblem.TIMEOUT, PsnPageProblem.BLANK, PsnPageProblem.CRASHED, PsnPageProblem.OUT_OF_MEMORY,
            PsnPageProblem.SSL, PsnPageProblem.HTTP, PsnPageProblem.OTHER))
            assertTrue(problem.name, PsnFallback.browserFirst(problem, failedBefore = false))
    }

    @Test
    fun beingOfflineKeepsTryAgainAsTheMainButton() {
        assertFalse(PsnFallback.browserFirst(PsnPageProblem.OFFLINE, failedBefore = false))
        assertFalse(PsnFallback.blamesWebView(PsnPageProblem.OFFLINE))
        assertTrue(PsnFallback.blamesWebView(PsnPageProblem.BLANK))
    }
}
