package eu.kanade.tachiyomi.ui.player

import eu.kanade.tachiyomi.ui.player.settings.AudioChannels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioChannelConfigurationTest {
    @Test
    fun `safe default uses system layout and unrestricted auto stays available`() {
        val properties = mutableMapOf<String, String>()
        val commands = mutableListOf<List<String>>()
        applyAudioChannels(AudioChannels.AutoSafe, setProperty = { k, v -> properties[k] = v }, command = {
            commands +=
                it.toList()
        })
        assertEquals("auto-safe", properties["audio-channels"])
        applyAudioChannels(AudioChannels.Auto, setProperty = { k, v -> properties[k] = v }, command = {
            commands +=
                it.toList()
        })
        assertEquals("auto", properties["audio-channels"])
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `channel changes preserve user filters and remove only the owned swap`() {
        val properties = mutableMapOf("af" to "@user:lavfi=[volume=0.8]")
        val commands = mutableListOf<List<String>>()
        val setProperty: (String, String) -> Unit = { k, v -> properties[k] = v }
        val command: (Array<String>) -> Unit = { commands += it.toList() }
        applyAudioChannels(AudioChannels.ReverseStereo, setProperty = setProperty, command = command)
        assertEquals("stereo", properties["audio-channels"])
        assertEquals(listOf("af", "add", "@ultrayomi_reverse_stereo:pan=[stereo|c0=c1|c1=c0]"), commands.single())
        applyAudioChannels(AudioChannels.Mono, AudioChannels.ReverseStereo, setProperty, command)
        assertEquals("mono", properties["audio-channels"])
        assertEquals(listOf("af", "remove", "@ultrayomi_reverse_stereo"), commands.last())
        assertEquals("@user:lavfi=[volume=0.8]", properties["af"])
    }

    @Test
    fun `selecting the current mode does not rebuild the audio pipeline`() {
        AudioChannels.entries.forEach { channels ->
            applyAudioChannels(
                channels,
                previous = channels,
                setProperty = { _, _ -> error("Unnecessary property change") },
                command = { error("Unnecessary filter rebuild") },
            )
        }
    }
}
