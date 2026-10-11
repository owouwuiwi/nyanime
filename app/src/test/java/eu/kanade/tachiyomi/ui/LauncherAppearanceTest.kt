package eu.kanade.tachiyomi.ui

import eu.kanade.domain.ui.NyanimeIdentity
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.data.theme.LauncherAppearance
import eu.kanade.tachiyomi.data.theme.launcherAppearance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LauncherAppearanceTest {
    @Test
    fun classicIdentityUsesExplicitModesAndFollowsOnlySystemWhenRequested() {
        for (dark in listOf(false, true)) {
            assertEquals(
                LauncherAppearance.CLASSIC_RED,
                launcherAppearance(NyanimeIdentity.CLASSIC, ThemeMode.DARK, dark),
            )
            assertEquals(
                LauncherAppearance.CLASSIC_ORANGE,
                launcherAppearance(NyanimeIdentity.CLASSIC, ThemeMode.LIGHT, dark),
            )
            assertEquals(
                if (dark) LauncherAppearance.CLASSIC_RED else LauncherAppearance.CLASSIC_ORANGE,
                launcherAppearance(NyanimeIdentity.CLASSIC, ThemeMode.SYSTEM, dark),
            )
        }
    }
}
