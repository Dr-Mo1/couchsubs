// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.account

import org.junit.Assert.*
import org.junit.Test

class AppIntegrityTest {
    @Test fun acceptsOfficialReleaseCert() {
        val cert = AppIntegrityRules.expectedCertSha256()
        assertEquals(32, cert.size)
        assertTrue(
            AppIntegrityRules.isValid(
                isDebugBuild = false,
                packageName = "com.targamgi.app",
                isDebuggableFlag = false,
                signerCertSha256List = listOf(cert),
            ),
        )
    }

    @Test fun rejectsModdedCertWrongPackageOrDebuggableInRelease() {
        val good = AppIntegrityRules.expectedCertSha256()
        val fake = ByteArray(32) { 0x11 }
        assertFalse(AppIntegrityRules.isValid(false, "com.targamgi.app", false, listOf(fake)))
        assertFalse(AppIntegrityRules.isValid(false, "com.targamgi.app", false, emptyList()))
        assertFalse(AppIntegrityRules.isValid(false, "com.cloned.mod", false, listOf(good)))
        assertFalse(AppIntegrityRules.isValid(false, "com.targamgi.app", true, listOf(good)))
    }

    @Test fun allowsDebugBuildForDevelopment() {
        assertTrue(AppIntegrityRules.isValid(true, "com.targamgi.app", true, emptyList()))
    }

    @Test fun remainingTimeCountsDownAccurately() {
        val status = AccountStatus(
            plan = "trial",
            planUntil = "2026-11-30T12:00:00Z",
            trialStartedAt = "2026-11-27T12:00:00Z",
            accessActive = true,
            deviceTrialUsedElsewhere = false,
            trialDays = 3,
            maintenanceOn = false,
            maintenanceMessage = "",
            announcement = "",
            minAppVersion = "",
            paidPlans = emptyList(),
        )
        val nowMs = AccountRules.epochMs("2026-11-28T06:15:30Z")!!
        val rem = AccountRules.remainingTime(status, nowMs)!!
        assertEquals(2, rem.days)
        assertEquals(5, rem.hours)
        assertEquals(44, rem.minutes)
        assertEquals(30, rem.seconds)

        // Permanent accounts return null (no countdown)
        val perm = status.copy(plan = "permanent", planUntil = null)
        assertNull(AccountRules.remainingTime(perm, nowMs))
    }
}
