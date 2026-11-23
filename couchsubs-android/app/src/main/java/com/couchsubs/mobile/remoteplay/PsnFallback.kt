// SPDX-License-Identifier: AGPL-3.0-only
// Sturdier Sony (PSN) sign-in. On some phones (DocMo's Xiaomi 13T Pro, HyperOS) Sony's page spins forever inside the in-app
// WebView while a normal browser works (chiaki-ng issues #495 and #664: Sony blocks some embedded browsers at times).
// Two cheap defenses live here, as pure functions so they are unit-tested: a Chrome-like user agent, and the rule for when
// the browser tab becomes the main way to sign in.
package com.couchsubs.mobile.remoteplay

object PsnFallback {
    /**
     * The WebView's default user agent minus the two tokens that mark it as an embedded WebView: "; wv" (inside the
     * parentheses) and "Version/4.0 ". The result reads like Chrome Mobile; everything else stays. Idempotent.
     */
    fun chromeLikeUserAgent(userAgent: String): String = userAgent.replace("; wv", "").replace("Version/4.0 ", "")

    /**
     * Whether the browser tab is offered as the main button: [failedBefore] (the in-app page already failed once on this phone)
     * or the current in-app attempt just failed ([problem]). Being offline is not the WebView's fault (a browser would fail
     * too), so it keeps "Try again" as the main button.
     */
    fun browserFirst(problem: PsnPageProblem?, failedBefore: Boolean): Boolean =
        failedBefore || (problem != null && blamesWebView(problem))

    /** An in-app failure worth remembering on this phone (not just a missing connection). */
    fun blamesWebView(problem: PsnPageProblem): Boolean = problem != PsnPageProblem.OFFLINE
}
