// SPDX-License-Identifier: AGPL-3.0-only
package com.couchsubs.mobile.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultEngineMigrationTest {
    @Test
    fun arabicUsersOnOldFreeDefaultOrFreshInstallMoveToServer() {
        assertEquals(
            DefaultEngineMigration.Step(EngineChoice.SERVER),
            DefaultEngineMigration.apply(migrated = false, stored = null, target = "ar", serverAvailable = true),
        )
        assertEquals(
            DefaultEngineMigration.Step(EngineChoice.SERVER),
            DefaultEngineMigration.apply(migrated = false, stored = EngineChoice.FREE, target = "ar", serverAvailable = true),
        )
    }

    @Test
    fun usersWhoChoseAnotherEngineKeepTheirChoice() {
        val customEngines = listOf(
            EngineChoice.DEEPL,
            EngineChoice.GEMINI,
            EngineChoice.OPENAI,
            EngineChoice.CLAUDE,
            EngineChoice.LOCAL,
            EngineChoice.SERVER,
        )
        for (choice in customEngines) {
            assertEquals(
                choice.name,
                DefaultEngineMigration.Step(null),
                DefaultEngineMigration.apply(migrated = false, stored = choice, target = "ar", serverAvailable = true),
            )
        }
    }

    @Test
    fun migrationRunsOnlyOnceAndRespectsLaterSwitchToFree() {
        assertNull(
            DefaultEngineMigration.apply(migrated = true, stored = EngineChoice.FREE, target = "ar", serverAvailable = true),
        )
        assertNull(
            DefaultEngineMigration.apply(migrated = true, stored = null, target = "ar", serverAvailable = true),
        )
    }

    @Test
    fun migrationSkipsWhenServerIsUnavailableOrTargetIsNotArabic() {
        assertNull(
            DefaultEngineMigration.apply(migrated = false, stored = EngineChoice.FREE, target = "ar", serverAvailable = false),
        )
        assertNull(
            DefaultEngineMigration.apply(migrated = false, stored = null, target = "ar", serverAvailable = false),
        )
        assertNull(
            DefaultEngineMigration.apply(migrated = false, stored = EngineChoice.FREE, target = "tr", serverAvailable = true),
        )
    }
}
