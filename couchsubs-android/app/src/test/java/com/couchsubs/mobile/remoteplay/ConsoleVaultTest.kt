// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.remoteplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsoleVaultTest {
    private val account1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val account2 = byteArrayOf(9, 10, 11, 12, 13, 14, 15, 16)

    private fun console(host: String, name: String, seed: Byte) =
        RegisteredConsole(host, name, byteArrayOf(seed, 1, 2), byteArrayOf(seed, 3, 4))

    @Test
    fun remembersBothPsnAccountsSeparatelyForSameConsole() {
        val reg1 = console("192.168.1.15", "DrMo-Ps", 10)
        val reg2 = console("192.168.1.15", "DrMo-Ps", 20)

        var vault = ConsoleVault.save(null, account1, reg1)
        vault = ConsoleVault.save(vault, account2, reg2)

        val found1 = ConsoleVault.find(vault, account1)!!
        val found2 = ConsoleVault.find(vault, account2)!!
        assertArrayEquals(reg1.registKey, found1.registKey)
        assertArrayEquals(reg2.registKey, found2.registKey)
        assertEquals("DrMo-Ps", found1.name)
        assertEquals("DrMo-Ps", found2.name)
    }

    @Test
    fun updatingHostOrReRegisteringPreservesOtherAccount() {
        val reg1 = console("192.168.1.15", "DrMo-Ps", 10)
        val reg2 = console("192.168.1.15", "DrMo-Ps", 20)
        var vault = ConsoleVault.save(null, account1, reg1)
        vault = ConsoleVault.save(vault, account2, reg2)

        vault = ConsoleVault.save(vault, account1, reg1.withHost("192.168.1.99"))

        assertEquals("192.168.1.99", ConsoleVault.find(vault, account1)!!.host)
        assertEquals("192.168.1.15", ConsoleVault.find(vault, account2)!!.host)
        assertArrayEquals(reg2.registKey, ConsoleVault.find(vault, account2)!!.registKey)
    }

    @Test
    fun migratesLegacySingleConsoleWithoutLosingIt() {
        val legacy = console("192.168.1.15", "DrMo-Ps", 30)
        val vault = ConsoleVault.migrate(null, account1, legacy)

        val restored = ConsoleVault.find(vault, account1)!!
        assertArrayEquals(legacy.registKey, restored.registKey)
        assertNull(ConsoleVault.find(vault, account2))
    }

    @Test
    fun handlesMalformedVaultJsonSafely() {
        assertNull(ConsoleVault.find("{bad", account1))
        val reg1 = console("192.168.1.15", "DrMo-Ps", 40)
        val vault = ConsoleVault.save("{bad", account1, reg1)
        assertArrayEquals(reg1.registKey, ConsoleVault.find(vault, account1)!!.registKey)
    }
}
