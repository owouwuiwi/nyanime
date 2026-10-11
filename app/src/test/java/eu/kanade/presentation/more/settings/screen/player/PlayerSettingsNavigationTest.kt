package eu.kanade.presentation.more.settings.screen.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

class PlayerSettingsNavigationTest {
    @Test
    fun `player settings can survive Android saving the navigation stack`() {
        for (mainSettings in listOf(false, true)) {
            val screen = PlayerSettingsMainScreen(mainSettings)
            val bytes = ByteArrayOutputStream().also { buffer ->
                ObjectOutputStream(buffer).use { it.writeObject(screen) }
            }.toByteArray()
            val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use {
                it.readObject() as PlayerSettingsMainScreen
            }
            assertEquals(screen.key, restored.key)
        }
    }
}
