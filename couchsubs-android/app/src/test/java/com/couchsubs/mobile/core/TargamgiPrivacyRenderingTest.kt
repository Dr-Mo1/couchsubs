// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TargamgiPrivacyRenderingTest {
    @Test fun switchesAreOffAndLatinIsIsolatedInsideArabic() {
        assertFalse(TargamgiPrivacy.SHARING); assertFalse(TargamgiPrivacy.TELEMETRY); assertFalse(TargamgiPrivacy.PROMO)
        assertEquals("Hello Jason.", ArabicBidi.isolateLatin("Hello Jason."))
        assertTrue(ArabicBidi.isolateLatin("شرطة VCPD هنا").contains("\u2068VCPD\u2069"))
    }
    @Test fun sourceIdentityAndAssetsStayPrivateAndRenamed() {
        val root = File(System.getProperty("targamgi.repoRoot", ".."))
        val app = File(root, "couchsubs-android/app")
        val gradle = File(app, "build.gradle").readText()
        assertTrue(gradle.contains("applicationId 'com.targamgi.app'"))
        assertTrue(gradle.contains("namespace 'com.couchsubs.mobile'"))
        assertTrue(gradle.contains("def hasGoogleServices = false"))
        assertTrue(gradle.contains("targamgi.dataDir"))
        val manifest = File(app, "src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("FirebaseInitProvider"))
        assertTrue(manifest.contains("tools:node=\"remove\""))
        val arabic = File(app, "src/main/res/values/strings.xml").readText() // Arabic is the default locale now (values-ar is gone)
        assertTrue(arabic.contains("ترجمجي"))
        val page = File(app, "src/main/assets/second-screen.html").readText()
        assertTrue(page.contains("unicode-bidi: plaintext"))
        assertTrue(page.contains("\u2068") || page.contains("\\u2068"))
        for (locale in listOf("values", "values-en")) {
            val strings = File(app, "src/main/res/$locale/strings.xml").readText()
            assertTrue(strings.contains(if (locale == "values") "نسخة معدّلة بتاريخ 2026-11-23" else "modified version (2026-11-23)"))
            assertTrue(strings.contains(if (locale == "values") "سنان يورولماز" else "Sinan Yorulmaz"))
        }
    }
}
