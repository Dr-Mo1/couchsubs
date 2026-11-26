// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.account

import com.couchsubs.mobile.translation.GateError
import com.couchsubs.mobile.translation.Http
import com.couchsubs.mobile.translation.TargamgiGate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.OffsetDateTime

class SubTvAccountTest {
    private class MemoryStore(var session: Session? = null) : SessionStore {
        override fun load() = session
        override fun save(session: Session?) {
            this.session = session
        }
    }

    private class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

    private val base = TargamgiGate.SUPABASE_URL
    private val now = 1_000_000_000L

    private fun tokens(access: String, refresh: String, expiresIn: Long = 3600, user: String = "3f2a9c1e-1111-2222-3333-444455556666") =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":$expiresIn,"user":{"id":"$user"}}"""

    private fun statusJson(plan: String = "trial", planUntil: String? = null, trialStartedAt: String? = null, active: Boolean = false,
                           elsewhere: Boolean = false, announcement: String = "", minVersion: String = "") =
        """{"account":{"plan":"$plan","plan_until":${planUntil?.let { "\"$it\"" }},"trial_started_at":${trialStartedAt?.let { "\"$it\"" }}},
            "access_active":$active,"device_trial_used_elsewhere":$elsewhere,
            "config":{"trial_days":3,"maintenance_on":false,"maintenance_message":"","announcement":"$announcement","min_app_version":"$minVersion"},
            "usage":{"daily_used":0,"daily_cap":100,"monthly_used":0,"monthly_cap":2000},
            "paid_plans":[{"id":"monthly_30","name":"Monthly","price_egp":100,"duration_days":30},{"id":"half","name":"Half","price_egp":49.5,"duration_days":15}]}"""

    private fun status(plan: String = "trial", active: Boolean = true, started: String? = "2026-11-26T10:00:00+00:00",
                       elsewhere: Boolean = false, until: String? = null, trialDays: Int = 3) =
        AccountStatus(plan, until, started, active, elsewhere, trialDays, false, "", "", "", emptyList())

    // ---- the token-refresh decision ----

    @Test
    fun tokenIsRefreshedWhenFewerThan120SecondsAreLeft() {
        assertTrue(AccountRules.needsRefresh(expiresAtMs = now + 119_999, nowMs = now))
        assertFalse(AccountRules.needsRefresh(expiresAtMs = now + 120_000, nowMs = now))
        assertFalse(AccountRules.needsRefresh(expiresAtMs = now + 3_600_000, nowMs = now))
        assertTrue(AccountRules.needsRefresh(expiresAtMs = now - 1, nowMs = now))
    }

    @Test
    fun noSessionSignsInOnceThenReusesTheToken() {
        val calls = mutableListOf<Call>()
        val http = Http { method, url, headers, body, _ -> calls += Call(method, url, headers, body); 200 to tokens("A1", "R1") }
        val store = MemoryStore()
        val client = SubTvAccountClient(http, store, "dev-12345", clock = { now })

        assertEquals("A1", client.accessToken())
        assertEquals("A1", client.accessToken())

        assertEquals(1, calls.size)
        assertEquals("POST", calls[0].method)
        assertEquals("$base/auth/v1/signup", calls[0].url)
        assertEquals(TargamgiGate.PUBLISHABLE_KEY, calls[0].headers["apikey"])
        assertEquals("application/json", calls[0].headers["Content-Type"])
        assertEquals("{}", calls[0].body)
        assertEquals(now + 3_600_000, store.session?.expiresAtMs)
        assertEquals("3F2A9C1E", AccountRules.accountNumber(client.userId()))
    }

    @Test
    fun tokenWithMoreThan120SecondsLeftIsReusedWithoutAnyCall() {
        val http = Http { _, _, _, _, _ -> throw AssertionError("no call expected") }
        val client = SubTvAccountClient(http, MemoryStore(Session("old", "R1", now + 120_000, "u")), "dev-12345", clock = { now })
        assertEquals("old", client.accessToken())
    }

    @Test
    fun tokenWithFewerThan120SecondsLeftIsRefreshedWithTheRefreshToken() {
        val calls = mutableListOf<Call>()
        val http = Http { method, url, headers, body, _ -> calls += Call(method, url, headers, body); 200 to tokens("A2", "R2") }
        val store = MemoryStore(Session("old", "R1", now + 119_000, "u"))
        val client = SubTvAccountClient(http, store, "dev-12345", clock = { now })

        assertEquals("A2", client.accessToken())

        assertEquals(1, calls.size)
        assertEquals("$base/auth/v1/token?grant_type=refresh_token", calls[0].url)
        assertEquals(TargamgiGate.PUBLISHABLE_KEY, calls[0].headers["apikey"])
        assertEquals("R1", JSONObject(calls[0].body.orEmpty()).getString("refresh_token"))
        assertEquals("R2", store.session?.refreshToken)
    }

    @Test
    fun failedRefreshKeepsTheOldTokenWhileItIsStillValidAndGivesNoneAfter() {
        val offline = Http { _, _, _, _, _ -> throw IOException("offline") }
        val stillValid = SubTvAccountClient(offline, MemoryStore(Session("old", "R1", now + 60_000, "u")), "dev-12345", clock = { now })
        assertEquals("old", stillValid.accessToken())
        val ended = SubTvAccountClient(offline, MemoryStore(Session("old", "R1", now - 1, "u")), "dev-12345", clock = { now })
        assertNull(ended.accessToken())
    }

    @Test
    fun refreshTokenTheServerRejectsStartsANewAnonymousSession() {
        val urls = mutableListOf<String>()
        val http = Http { _, url, _, _, _ ->
            urls += url
            if (url.contains("grant_type=refresh_token")) 400 to """{"error":"invalid_grant"}""" else 200 to tokens("A3", "R3")
        }
        val store = MemoryStore(Session("old", "R1", now + 10_000, "u"))
        val client = SubTvAccountClient(http, store, "dev-12345", clock = { now })

        assertEquals("A3", client.accessToken())
        assertEquals(listOf("$base/auth/v1/token?grant_type=refresh_token", "$base/auth/v1/signup"), urls)
        assertEquals("R3", store.session?.refreshToken)
    }

    @Test
    fun rateLimitedRefreshDoesNotThrowTheSessionAway() {
        val urls = mutableListOf<String>()
        val http = Http { _, url, _, _, _ -> urls += url; 429 to """{"error":"rate_limit"}""" }
        val store = MemoryStore(Session("old", "R1", now + 10_000, "u"))
        val client = SubTvAccountClient(http, store, "dev-12345", clock = { now })

        assertEquals("old", client.accessToken())
        assertEquals(1, urls.size)
        assertEquals("R1", store.session?.refreshToken)
    }

    @Test
    fun a401RefreshesOnceAndRetriesOnce() {
        val calls = mutableListOf<Call>()
        var statusCalls = 0
        val http = Http { method, url, headers, body, _ ->
            calls += Call(method, url, headers, body)
            when {
                url.endsWith("/functions/v1/account-status") -> if (++statusCalls == 1) 401 to """{"error":"unauthorized"}""" else 200 to statusJson(active = true)
                else -> 200 to tokens("A2", "R2")
            }
        }
        val client = SubTvAccountClient(http, MemoryStore(Session("A1", "R1", now + 3_600_000, "u")), "dev-12345", clock = { now })

        val reply = client.fetchStatus()

        assertTrue(reply is StatusReply.Ok)
        assertEquals(3, calls.size)
        assertEquals("Bearer A1", calls[0].headers["Authorization"])
        assertTrue(calls[1].url.contains("grant_type=refresh_token"))
        assertEquals("Bearer A2", calls[2].headers["Authorization"])
        assertEquals("dev-12345", JSONObject(calls[2].body.orEmpty()).getString("device_id"))
        assertEquals(TargamgiGate.PUBLISHABLE_KEY, calls[2].headers["apikey"])
    }

    @Test
    fun aSecond401IsNotRetriedAgain() {
        var statusCalls = 0
        val http = Http { _, url, _, _, _ ->
            if (url.endsWith("/functions/v1/account-status")) { statusCalls++; 401 to """{"error":"unauthorized"}""" } else 200 to tokens("A2", "R2")
        }
        val client = SubTvAccountClient(http, MemoryStore(Session("A1", "R1", now + 3_600_000, "u")), "dev-12345", clock = { now })

        assertEquals(StatusReply.Failed(401), client.fetchStatus())
        assertEquals(2, statusCalls)
    }

    // ---- the server's status reply ----

    @Test
    fun statusReplyIsParsedIncludingNullDatesAndPlans() {
        val parsed = AccountStatus.parse(statusJson(announcement = "Hello", minVersion = "1.0.50"))
        assertNotNull(parsed)
        parsed!!
        assertEquals("trial", parsed.plan)
        assertNull(parsed.planUntil)
        assertNull(parsed.trialStartedAt)
        assertFalse(parsed.accessActive)
        assertEquals(3, parsed.trialDays)
        assertEquals("Hello", parsed.announcement)
        assertEquals("1.0.50", parsed.minAppVersion)
        assertEquals(listOf(Plan("monthly_30", "Monthly", 100.0, 30), Plan("half", "Half", 49.5, 15)), parsed.paidPlans)
        assertEquals("100", AccountRules.priceText(100.0))
        assertEquals("49.5", AccountRules.priceText(49.5))
        assertNull(AccountStatus.parse("not json"))
    }

    // ---- the "trial not started" rule and the card ----

    @Test
    fun aTrialThatHasNotStartedIsAllowedEvenThoughAccessIsNotActiveYet() {
        val fresh = status(active = false, started = null)
        assertEquals(AccessKind.TRIAL_NOT_STARTED, AccountRules.kind(fresh))
        assertFalse(AccountRules.blocksTranslation(fresh, null))
    }

    @Test
    fun aTrialNotStartedOnADeviceThatUsedItsTrialElsewhereIsEnded() {
        val used = status(active = false, started = null, elsewhere = true)
        assertEquals(AccessKind.ENDED, AccountRules.kind(used))
        assertTrue(AccountRules.blocksTranslation(used, null))
    }

    @Test
    fun accessThatIsNotActiveAfterTheTrialStartedIsEnded() {
        assertEquals(AccessKind.ENDED, AccountRules.kind(status(active = false)))
        assertEquals(AccessKind.ENDED, AccountRules.kind(status(plan = "blocked", active = false, started = null, elsewhere = true)))
        assertEquals(AccessKind.ENDED, AccountRules.kind(status(plan = "paid", active = false, until = "2026-10-01T00:00:00+00:00")))
    }

    @Test
    fun activePlansReadAsTrialSubscribedOrPermanent() {
        assertEquals(AccessKind.TRIAL, AccountRules.kind(status(active = true)))
        assertEquals(AccessKind.SUBSCRIBED, AccountRules.kind(status(plan = "paid", active = true)))
        assertEquals(AccessKind.SUBSCRIBED, AccountRules.kind(status(plan = "code", active = true)))
        assertEquals(AccessKind.PERMANENT, AccountRules.kind(status(plan = "permanent", active = true, started = null)))
    }

    @Test
    fun trialDaysLeftRoundUpAndNeverGoBelowZero() {
        fun at(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        val running = status(until = "2026-11-29T10:00:00+00:00")
        assertEquals(3, AccountRules.trialDaysLeft(running, at("2026-11-26T10:00:00+00:00")))
        assertEquals(3, AccountRules.trialDaysLeft(running, at("2026-11-26T10:00:01+00:00")))
        assertEquals(1, AccountRules.trialDaysLeft(running, at("2026-11-28T11:00:00+00:00")))
        assertEquals(0, AccountRules.trialDaysLeft(running, at("2026-11-29T10:00:00+00:00")))
        assertEquals(0, AccountRules.trialDaysLeft(running, at("2026-12-05T10:00:00+00:00")))
        // No plan_until: the start plus the trial length.
        val noEnd = status(until = null, started = "2026-11-26T10:00:00+00:00")
        assertEquals(3, AccountRules.trialDaysLeft(noEnd, at("2026-11-26T10:00:00+00:00")))
    }

    // ---- error code -> UI state ----

    @Test
    fun subscribeScreenShowsWhenAccessEndedRegardlessOfEngine() {
        val ended = status(active = false)
        assertEquals(AccountScreen.SUBSCRIBE, AccountRules.screen(ended, null, "1.0.48", serverEngine = true))
        assertEquals(AccountScreen.SUBSCRIBE, AccountRules.screen(ended, null, "1.0.48", serverEngine = false))
        // The server just said "trial_expired" before any status was read.
        assertEquals(AccountScreen.SUBSCRIBE, AccountRules.screen(null, GateError.ACCESS_ENDED, "1.0.48", serverEngine = true))
        assertEquals(AccountScreen.SUBSCRIBE, AccountRules.screen(null, GateError.ACCESS_ENDED, "1.0.48", serverEngine = false))
        // A fresh trial that has not started, a daily cap, maintenance, an account problem and "no status yet" never block.
        assertEquals(AccountScreen.NONE, AccountRules.screen(status(active = false, started = null), null, "1.0.48", serverEngine = true))
        for (error in listOf(GateError.CAP_REACHED, GateError.MAINTENANCE, GateError.ACCOUNT_PROBLEM, null))
            assertEquals(AccountScreen.NONE, AccountRules.screen(status(active = true), error, "1.0.48", serverEngine = true))
        assertEquals(AccountScreen.NONE, AccountRules.screen(null, null, "1.0.48", serverEngine = true))
    }

    @Test
    fun oldAppVersionBlocksEverythingWithTheUpdateScreen() {
        val old = AccountStatus.parse(statusJson(active = true, minVersion = "1.0.49"))!!
        assertEquals(AccountScreen.UPDATE_REQUIRED, AccountRules.screen(old, null, "1.0.48", serverEngine = false))
        assertEquals(AccountScreen.NONE, AccountRules.screen(old, null, "1.0.49", serverEngine = true))
        assertEquals(AccountScreen.NONE, AccountRules.screen(old.copy(minAppVersion = ""), null, "1.0.48", serverEngine = true))
        assertTrue(AccountRules.isOlder("1.0.48", "1.0.49"))
        assertFalse(AccountRules.isOlder("1.0.48", "1.0.48"))
        assertFalse(AccountRules.isOlder("1.1", "1.0.9"))
        assertTrue(AccountRules.isOlder("1.0", "1.0.1"))
        assertTrue(AccountRules.isOlder("0.1.0-beta.37", "1.0.48"))
        assertTrue(AccountRules.isOlder("1.0.9", "1.0.10"))
    }

    @Test
    fun redeemErrorCodesMapToTheirMessages() {
        assertEquals(RedeemProblem.INVALID_CODE, AccountRules.redeemProblem("invalid_code"))
        assertEquals(RedeemProblem.CODE_EXPIRED, AccountRules.redeemProblem("code_expired"))
        assertEquals(RedeemProblem.CODE_EXHAUSTED, AccountRules.redeemProblem("code_exhausted"))
        assertEquals(RedeemProblem.ALREADY_REDEEMED, AccountRules.redeemProblem("already_redeemed"))
        assertEquals(RedeemProblem.ALREADY_PERMANENT, AccountRules.redeemProblem("already_permanent"))
        assertEquals(RedeemProblem.CODE_INACTIVE, AccountRules.redeemProblem("code_inactive"))
        assertEquals(RedeemProblem.NETWORK, AccountRules.redeemProblem("network"))
        assertEquals(RedeemProblem.OTHER, AccountRules.redeemProblem("redeem_failed"))
        assertEquals(RedeemProblem.OTHER, AccountRules.redeemProblem("http_500"))
    }

    @Test
    fun redeemSendsTheCodeAndDeviceAndReadsTheReply() {
        val bodies = mutableListOf<String>()
        val replies = ArrayDeque(listOf(
            200 to """{"ok":true,"code":"ABCDEFGHJK","grant_days":30,"plan":"code","plan_until":"2026-12-30T10:00:00+00:00"}""",
            409 to """{"ok":false,"error":"already_redeemed","message":"You have already redeemed this access code"}""",
            400 to "<html>bad gateway</html>",
        ))
        val http = Http { _, url, headers, body, _ ->
            assertTrue(url.endsWith("/functions/v1/redeem-code"))
            assertEquals("Bearer A1", headers["Authorization"])
            assertEquals(TargamgiGate.PUBLISHABLE_KEY, headers["apikey"])
            bodies += body.orEmpty()
            replies.removeFirst()
        }
        val client = SubTvAccountClient(http, MemoryStore(Session("A1", "R1", now + 3_600_000, "u")), "dev-12345", clock = { now })

        assertEquals(RedeemReply.Success("code", "2026-12-30T10:00:00+00:00"), client.redeem("  abcd-efgh-jk "))
        assertEquals(RedeemReply.Failure("already_redeemed"), client.redeem("ABCDEFGHJK"))
        assertEquals(RedeemReply.Failure("http_400"), client.redeem("ABCDEFGHJK"))
        assertEquals("abcd-efgh-jk", JSONObject(bodies[0]).getString("code"))
        assertEquals("dev-12345", JSONObject(bodies[0]).getString("device_id"))

        val offline = SubTvAccountClient(Http { _, _, _, _, _ -> throw IOException("offline") },
            MemoryStore(Session("A1", "R1", now + 3_600_000, "u")), "dev-12345", clock = { now })
        assertEquals(RedeemReply.Failure("network"), offline.redeem("ABCDEFGHJK"))
    }

    // ---- small rules ----

    @Test
    fun accountNumberIsTheFirstEightHexCharactersUpperCased() {
        assertEquals("3F2A9C1E", AccountRules.accountNumber("3f2a9c1e-1111-2222-3333-444455556666"))
        assertEquals("3F2A9C1E", AccountRules.accountNumber("3f2a9c1e11112222"))
        assertNull(AccountRules.accountNumber(null))
        assertNull(AccountRules.accountNumber(""))
    }

    @Test
    fun deviceIdIsTheAndroidIdElseASavedRandomId() {
        assertEquals("9774d56d682e549c" to false, AccountRules.deviceId("9774d56d682e549c", null) { "generated" })
        assertEquals("saved-id" to false, AccountRules.deviceId(null, "saved-id") { "generated" })
        assertEquals("saved-id" to false, AccountRules.deviceId("  ", "saved-id") { "generated" })
        assertEquals("generated" to true, AccountRules.deviceId(null, null) { "generated" })
        assertEquals("generated" to true, AccountRules.deviceId("abc", "xy") { "generated" })
    }
}
