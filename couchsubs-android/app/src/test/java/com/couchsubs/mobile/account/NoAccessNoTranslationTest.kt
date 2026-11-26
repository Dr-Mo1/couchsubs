// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.account

import com.couchsubs.mobile.core.PackMatch
import com.couchsubs.mobile.core.TextTranslator
import com.couchsubs.mobile.translation.AccessGatedTranslator
import com.couchsubs.mobile.translation.GateError
import com.couchsubs.mobile.translation.GateException
import com.couchsubs.mobile.translation.PackFirstTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoAccessNoTranslationTest {
    private fun status(
        plan: String = "trial",
        active: Boolean = false,
        started: String? = "2026-11-20T10:00:00+00:00",
        elsewhere: Boolean = false,
    ) = AccountStatus(plan, null, started, active, elsewhere, 3, false, "", "", "", emptyList())

    @Test
    fun endedAccessBlocksTranslationAndShowsSubscribeScreenForEveryEngine() {
        val endedTrial = status(plan = "trial", active = false, started = "2026-11-20T10:00:00+00:00")
        val usedElsewhere = status(plan = "trial", active = false, started = null, elsewhere = true)
        val expiredPaid = status(plan = "paid", active = false, started = null)
        val blocked = status(plan = "blocked", active = false, started = null)

        for (st in listOf(endedTrial, usedElsewhere, expiredPaid, blocked)) {
            assertFalse(AccountRules.canTranslate(st, null))
            assertTrue(AccountRules.blocksTranslation(st, null))
            assertEquals(AccountScreen.SUBSCRIBE, AccountRules.screen(st, null, "1.0.48", serverEngine = true))
            assertEquals(AccountScreen.SUBSCRIBE, AccountRules.screen(st, null, "1.0.48", serverEngine = false))
        }

        // Gate refusal also blocks everything even before status updates.
        assertFalse(AccountRules.canTranslate(null, GateError.ACCESS_ENDED))
        assertEquals(AccountScreen.SUBSCRIBE, AccountRules.screen(null, GateError.ACCESS_ENDED, "1.0.48", serverEngine = false))
    }

    @Test
    fun activeAccessAndUnstartedTrialAllowTranslation() {
        assertTrue(AccountRules.canTranslate(status(plan = "trial", active = false, started = null, elsewhere = false), null))
        assertTrue(AccountRules.canTranslate(status(plan = "trial", active = true), null))
        assertTrue(AccountRules.canTranslate(status(plan = "paid", active = true), null))
        assertTrue(AccountRules.canTranslate(status(plan = "permanent", active = true, started = null), null))
    }

    @Test
    fun accessGatedTranslatorStopsLocalPackCorrectionsAndEngineWhenAccessIsInactive() {
        var allowed = true
        var packLookups = 0
        var engineCalls = 0
        var blockedCallbacks = 0

        val engine = object : TextTranslator {
            override val translationLanguage = "ar"
            override fun translate(text: String): String {
                engineCalls++
                return "محرك: $text"
            }

            override fun translateAll(texts: List<String>): List<String> {
                engineCalls++
                return texts.map { "محرك: $it" }
            }
        }
        val packFirst = PackFirstTranslator(engine) { text ->
            packLookups++
            if (text == "Run!") PackMatch("اجري!", "gta-6-ar") else null
        }
        val gated = AccessGatedTranslator(packFirst, isAllowed = { allowed }, onBlocked = { blockedCallbacks++ })

        // Active subscription: pack lookup and engine both work.
        assertEquals("اجري!", gated.translate("Run!"))
        assertEquals(1, packLookups)
        assertEquals(0, engineCalls)
        assertEquals("محرك: Hello", gated.translate("Hello"))
        assertEquals(2, packLookups)
        assertEquals(1, engineCalls)

        // Subscription ends: NOTHING runs — not even the local pack or corrections lookup.
        allowed = false
        val err1 = assertThrows(GateException::class.java) { gated.translate("Run!") }
        assertEquals(GateError.ACCESS_ENDED, err1.error)
        val err2 = assertThrows(GateException::class.java) { gated.translateAll(listOf("Run!", "Hello")) }
        assertEquals(GateError.ACCESS_ENDED, err2.error)
        assertEquals(2, packLookups)
        assertEquals(1, engineCalls)
        assertEquals(2, blockedCallbacks)
    }
}
