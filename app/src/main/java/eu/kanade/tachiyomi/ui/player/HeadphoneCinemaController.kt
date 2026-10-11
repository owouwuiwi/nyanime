package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import eu.kanade.tachiyomi.ui.player.settings.AudioChannels
import eu.kanade.tachiyomi.ui.player.settings.AudioPreferences
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Activity-owned: callbacks, preference collectors and polling stop before MPV is released. */
class HeadphoneCinemaController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: AudioPreferences,
    private val playerAvailable: () -> Boolean,
) {
    private val mutableState = MutableStateFlow(HeadphoneCinemaState())
    val state = mutableState.asStateFlow()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val filter = CinemaFilter(MPVLib::command, ::filterPresent)
    private val jobs = mutableListOf<Job>()
    private var preparation: Job? = null
    private var directory: File? = null
    private var closed = false
    private var mediaReady = false
    private var failed = false
    private var updating = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        audio.registerAudioDeviceCallback(callback, handler)
        jobs += preferences.audioChannels().changes().onEach { refresh() }.launchIn(scope)
        // Routing can also change without a device being disconnected (Bluetooth output selector).
        jobs += scope.launch {
            preferences.headphoneCinema().changes().distinctUntilChanged().collectLatest { enabled ->
                refresh()
                if (enabled) {
                    while (isActive) {
                        delay(1_000)
                        refresh()
                    }
                }
            }
        }
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        if (closed) return
        failed = false
        preferences.headphoneCinema().set(enabled)
        refresh()
    }

    fun fileLoading() {
        if (closed) return
        mediaReady = false
        failed = false
        refresh()
    }

    fun fileLoaded() {
        mediaReady = true
        refresh()
    }

    fun audioTrackChanging() {
        if (closed || !playerAvailable()) return
        failed = false
        filter.remove()
    }

    fun nativeError() {
        if (closed || !playerAvailable() || state.value.status != CinemaStatus.Active) return
        failed = true
        refresh()
    }

    fun refresh() {
        if (closed || updating || !playerAvailable()) return
        updating = true
        try {
            val enabled = preferences.headphoneCinema().get()
            val layout = if (mediaReady) {
                CinemaLayout.fromNative(
                    MPVLib.getPropertyString("audio-params/channels"),
                )
            } else {
                null
            }
            val channels = preferences.audioChannels().get()
            var status = when {
                !enabled -> CinemaStatus.Original
                failed -> CinemaStatus.Unavailable
                !headphoneOutput() -> CinemaStatus.ConnectHeadphones
                channels != AudioChannels.Auto && channels != AudioChannels.AutoSafe -> CinemaStatus.ChannelOverride
                hasOtherFilters() -> CinemaStatus.CustomFilters
                !mediaReady -> CinemaStatus.Preparing
                layout == null -> CinemaStatus.UnsupportedTrack
                directory == null -> CinemaStatus.Preparing
                else -> CinemaStatus.Active
            }
            if (enabled && layout != null && directory == null && !failed) prepareAssets()
            if (!filter.apply(layout.takeIf { status == CinemaStatus.Active }, directory?.absolutePath.orEmpty())) {
                failed = true
                status = CinemaStatus.Unavailable
            }
            mutableState.value = HeadphoneCinemaState(enabled, status, layout)
        } finally {
            updating = false
        }
    }

    private fun filterPresent(): Boolean {
        return parseAudioFilters(MPVLib.getPropertyString("af"))
            ?.any { it.startsWith("@${CinemaFilter.LABEL}:") } == true
    }

    private fun hasOtherFilters(): Boolean {
        return parseAudioFilters(MPVLib.getPropertyString("af"))
            ?.any { !it.startsWith("@${CinemaFilter.LABEL}:") } != false
    }

    private fun headphoneOutput(): Boolean = runCatching {
        val outputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audio.getAudioDevicesForAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
        } else {
            // Older Android does not expose the media route. Wired/USB connections are reliable;
            // Bluetooth is eligible only while A2DP is on, not merely paired.
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                it.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || audio.isBluetoothA2dpOn
            }
        }
        outputs.isNotEmpty() &&
            outputs.any { it.type in HEADPHONE_TYPES } &&
            (Build.VERSION.SDK_INT < 33 || outputs.none { it.type !in HEADPHONE_TYPES })
    }.getOrDefault(false)

    private fun prepareAssets() {
        if (preparation?.isActive == true) return
        preparation = scope.launch {
            try {
                directory = withContext(Dispatchers.IO) {
                    File(context.noBackupFilesDir, "headphone-cinema-v1").also { folder ->
                        check(folder.isDirectory || folder.mkdirs())
                        CinemaLayout.entries.forEach { layout ->
                            // Always replace from the signed APK, so an interrupted copy is never reused.
                            val temporary = File(folder, "${layout.asset}.tmp")
                            context.assets.open("audio/cinema/${layout.asset}").use { input ->
                                temporary.outputStream().use(input::copyTo)
                            }
                            check(temporary.renameTo(File(folder, layout.asset)))
                        }
                    }
                }
            } catch (_: java.io.IOException) {
                failed = true
            } catch (_: IllegalStateException) {
                failed = true
            }
            refresh()
        }
    }

    fun close() {
        closed = true
        audio.unregisterAudioDeviceCallback(callback)
        jobs.forEach(Job::cancel)
        preparation?.cancel()
        filter.close()
    }

    private companion object {
        // Android cannot distinguish speakers from headphones behind A2DP or a USB DAC.
        // The user explicitly enables this mode; the UI explains it is for headphones.
        val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
