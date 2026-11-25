// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.remoteplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovedConsoleTest {
    /** Adresi değişen PS5 yalnız adı tutarsa seçilir; ağda tek PS5 olsa bile başka evin/odanın PS5'i seçilmez (GitHub #425; Mac ve Windows ile aynı). */
    @Test
    fun movedConsoleIsPickedOnlyWhenCertain() {
        val a = ConsoleInfo("192.168.1.20", "PS5-A", true)
        val b = ConsoleInfo("192.168.1.21", "PS5-B", true)
        assertNull(ConsoleDiscovery.pickMoved(listOf(a), "other"))
        assertEquals(a, ConsoleDiscovery.pickMoved(listOf(a), "PS5-A"))
        assertEquals(b, ConsoleDiscovery.pickMoved(listOf(a, b), "PS5-B"))
        assertNull(ConsoleDiscovery.pickMoved(listOf(a, b), "other"))
        assertNull(ConsoleDiscovery.pickMoved(emptyList(), "PS5-A"))
    }

    @Test
    fun otherConsoleAtSavedAddressIsNotTheRegisteredOne() {
        val a = ConsoleInfo("192.168.1.20", "PS5-A", true)
        assertTrue(ConsoleDiscovery.isRegistered(a, "PS5-A"))
        assertFalse(ConsoleDiscovery.isRegistered(a, "PS5-B"))
        assertTrue(ConsoleDiscovery.isRegistered(a, ""))
    }

    /**
     * A2 item 5: the saved address failed, so the whole network is scanned for the registered PS5 by name. A PS5 that
     * moved is reported with its new address (the caller saves it); one that only answered the scan at the SAVED address is
     * returned too, unmoved, so its awake or rest state is used instead of connecting blind; none or two of that name: null.
     */
    @Test
    fun scanFindsTheRegisteredConsoleAtANewAddressOrAtTheSavedOne() {
        val moved = ConsoleInfo("192.168.1.31", "PS5-A", false)
        val other = ConsoleInfo("192.168.1.20", "PS5-B", true)
        assertEquals(ConsoleDiscovery.Relocation(moved, moved = true), ConsoleDiscovery.relocation(listOf(other, moved), "PS5-A", "192.168.1.15"))
        assertEquals(ConsoleDiscovery.Relocation(moved, moved = false), ConsoleDiscovery.relocation(listOf(moved), "PS5-A", "192.168.1.31"))
        assertNull(ConsoleDiscovery.relocation(listOf(other), "PS5-A", "192.168.1.15"))
        assertNull(ConsoleDiscovery.relocation(listOf(moved, moved.copy(host = "192.168.1.32")), "PS5-A", "192.168.1.15"))
        assertNull(ConsoleDiscovery.relocation(emptyList(), "PS5-A", "192.168.1.15"))
    }

    /** When nothing is found the message names the three things the player can check: PS5 on, same Wi-Fi, Remote Play enabled. */
    @Test
    fun notFoundMessageNamesPs5OnSameWifiAndRemotePlay() {
        val root = java.io.File(System.getProperty("targamgi.repoRoot", ".."))
        val strings = java.io.File(root, "couchsubs-android/app/src/main/res/values/strings.xml").readText()
        for (name in listOf("phase_not_found_host", "phase_not_found")) {
            val message = Regex("<string name=\"$name\">(.*)</string>").find(strings)!!.groupValues[1]
            assertTrue("$name: on, not in rest mode", message.contains("يعمل") && message.contains("وضع الراحة"))
            assertTrue("$name: same Wi-Fi", message.contains("واي فاي نفسها"))
            assertTrue("$name: Remote Play", message.contains("التشغيل عن بُعد") && message.contains("مفعّل"))
        }
    }
}
