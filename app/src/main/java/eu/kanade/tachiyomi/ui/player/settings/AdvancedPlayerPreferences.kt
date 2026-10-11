package eu.kanade.tachiyomi.ui.player.settings

import eu.kanade.tachiyomi.ui.player.Anime4KCalibration
import eu.kanade.tachiyomi.ui.player.Anime4KContentAnalysis
import eu.kanade.tachiyomi.ui.player.Anime4KEpisodeProfile
import eu.kanade.tachiyomi.ui.player.Anime4KMode
import eu.kanade.tachiyomi.ui.player.Anime4KProfile
import eu.kanade.tachiyomi.ui.player.Anime4KSelection
import eu.kanade.tachiyomi.ui.player.Anime4KSmartDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.security.MessageDigest

/** New installs opt in; existing installs retain the former enabled default unless explicitly changed. */
internal fun preserveSmartAutoStartOnUpgrade(store: PreferenceStore, previousVersion: Int) {
    val preference = store.getBoolean("pref_anime4k_smart_auto_start", false)
    if (!preference.isSet()) preference.set(previousVersion > 0)
}

class AdvancedPlayerPreferences(
    private val preferenceStore: PreferenceStore,
) {
    private val anime4kEffectiveModeFlow = MutableStateFlow(Anime4KMode.Off)
    private val anime4kEffectiveModeState = anime4kEffectiveModeFlow.asStateFlow()
    private val anime4kActiveSelectionFlow = MutableStateFlow(
        Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off),
    )
    private val anime4kActiveSelectionState = anime4kActiveSelectionFlow.asStateFlow()
    private val anime4kDiagnosticsFlow = MutableStateFlow(Anime4KSmartDiagnostics())
    private val anime4kDiagnosticsState = anime4kDiagnosticsFlow.asStateFlow()
    private var activeEpisodeKey: String? = null
    private var anime4kRoomActive = false
    private var anime4kUltraActive = false
    private var anime4kSessionSelection = Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off)

    fun mpvUserFiles() = preferenceStore.getBoolean("mpv_scripts", false)
    fun mpvConf() = preferenceStore.getString("pref_mpv_conf", "")
    fun mpvInput() = preferenceStore.getString("pref_mpv_input", "")
    fun anime4kSmartAutoStart() = preferenceStore.getBoolean("pref_anime4k_smart_auto_start", false)
    fun anime4kMode() = preferenceStore.getEnum("pref_anime4k_mode", Anime4KMode.Off)
    fun anime4kProfile() = preferenceStore.getEnum(
        "pref_anime4k_profile",
        if (anime4kMode().get() == Anime4KMode.Off) Anime4KProfile.Off else Anime4KProfile.Custom,
    )

    /** Effective player mode; unlike the preference, Smart's learned mode is session-local. */
    fun anime4kEffectiveMode(): StateFlow<Anime4KMode> = anime4kEffectiveModeState

    fun setAnime4kEffectiveMode(mode: Anime4KMode) {
        setAnime4kActiveSelection(anime4kSessionSelection.copy(mode = mode))
    }

    fun anime4kActiveSelection(): StateFlow<Anime4KSelection> = anime4kActiveSelectionState

    fun setAnime4kActiveSelection(selection: Anime4KSelection) {
        if (anime4kRoomActive || anime4kUltraActive) return
        anime4kSessionSelection = selection
        publishAnime4kSelection()
    }

    /** Temporarily disables rendering without replacing the episode's solo playback choice. */
    fun setAnime4kRoomActive(active: Boolean) {
        anime4kRoomActive = active
        publishAnime4kSelection()
        if (active) anime4kDiagnosticsFlow.value = Anime4KSmartDiagnostics()
    }

    fun setAnime4kUltraActive(active: Boolean) {
        anime4kUltraActive = active
        publishAnime4kSelection()
        if (active) anime4kDiagnosticsFlow.value = Anime4KSmartDiagnostics()
    }

    private fun publishAnime4kSelection() {
        val selection = if (anime4kRoomActive || anime4kUltraActive) {
            Anime4KSelection(Anime4KProfile.Off, Anime4KMode.Off)
        } else {
            anime4kSessionSelection
        }
        anime4kActiveSelectionFlow.value = selection
        anime4kEffectiveModeFlow.value = selection.mode
    }

    fun anime4kDiagnosticsEnabled() = preferenceStore.getBoolean("pref_anime4k_diagnostics", false)

    fun anime4kContentAnalysisEnabled() = preferenceStore.getBoolean(
        "pref_anime4k_content_analysis",
        Anime4KContentAnalysis.DEFAULT_ENABLED,
    )

    fun anime4kDiagnostics(): StateFlow<Anime4KSmartDiagnostics> = anime4kDiagnosticsState

    fun setAnime4kDiagnostics(diagnostics: Anime4KSmartDiagnostics) {
        anime4kDiagnosticsFlow.value =
            if (anime4kRoomActive || anime4kUltraActive) Anime4KSmartDiagnostics() else diagnostics
    }

    fun beginAnime4kSession() {
        activeEpisodeKey = null
    }

    fun loadAnime4kEpisodeProfile(animeId: Long?, episodeId: Long?): Anime4KEpisodeProfile {
        val key = episodeKey(animeId, episodeId)
        // A quality change or stream recovery keeps the choice made in the current player.
        val active = anime4kSessionSelection.takeIf { key != null && key == activeEpisodeKey }
        activeEpisodeKey = key
        val stored = activeEpisodeKey
            ?.let { preferenceStore.getString(it, "").get() }
            ?.let(::decodeEpisodeProfile)
        val candidate = stored ?: Anime4KEpisodeProfile(Anime4KProfile.Smart)
        val profile = when {
            active != null -> Anime4KEpisodeProfile(
                active.profile,
                if (active.profile == Anime4KProfile.Custom) active.mode else Anime4KMode.Off,
            )
            candidate.profile == Anime4KProfile.Smart && !anime4kSmartAutoStart().get() ->
                Anime4KEpisodeProfile(Anime4KProfile.Off)
            else -> candidate
        }
        // Only an explicit player choice is saved; the default must follow future setting changes.
        anime4kSessionSelection = profile.toSelection()
        publishAnime4kSelection()
        setAnime4kDiagnostics(
            Anime4KSmartDiagnostics(
                profile = profile.profile,
                mode = profile.toSelection().mode,
            ),
        )
        return profile
    }

    fun anime4kEpisodeProfile(animeId: Long?, episodeId: Long?): Anime4KEpisodeProfile? {
        return episodeKey(animeId, episodeId)
            ?.let { preferenceStore.getString(it, "").get() }
            ?.let(::decodeEpisodeProfile)
    }

    fun saveAnime4kEpisodeProfile(
        animeId: Long?,
        episodeId: Long?,
        profile: Anime4KEpisodeProfile,
    ) {
        if (anime4kRoomActive || anime4kUltraActive) return
        val key = episodeKey(animeId, episodeId) ?: return
        preferenceStore.getString(key, "").set(encodeEpisodeProfile(profile))
        if (key == activeEpisodeKey) {
            setAnime4kActiveSelection(profile.toSelection())
        }
    }

    fun anime4kCalibration(key: String): Anime4KCalibration? {
        val encoded = preferenceStore.getString(calibrationPreferenceKey(key), "").get()
        val fields = encoded.split('|')
        if (fields.size != 3) return null
        val mode = fields[0].let { value ->
            runCatching { Anime4KMode.valueOf(value) }.getOrNull()
        } ?: return null
        val probes = fields[1].toIntOrNull()?.coerceAtLeast(0) ?: return null
        val timestamp = fields[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return Anime4KCalibration(mode, probes, timestamp)
    }

    fun saveAnime4kCalibration(key: String, calibration: Anime4KCalibration) {
        preferenceStore.getString(calibrationPreferenceKey(key), "").set(
            listOf(
                calibration.maxStableMode.name,
                calibration.successfulProbes,
                calibration.updatedAtMillis,
            ).joinToString("|"),
        )
    }

    private fun Anime4KEpisodeProfile.toSelection(): Anime4KSelection = when (profile) {
        Anime4KProfile.Off -> Anime4KSelection(profile, Anime4KMode.Off)
        Anime4KProfile.Smart -> Anime4KSelection(profile, Anime4KMode.Off)
        Anime4KProfile.Maximum -> Anime4KSelection(profile, Anime4KMode.ModeAPlusHq)
        Anime4KProfile.Custom -> Anime4KSelection(profile, customMode)
    }

    private fun encodeEpisodeProfile(profile: Anime4KEpisodeProfile): String = listOf(
        profile.profile.name,
        profile.customMode.name,
    ).joinToString("|")

    private fun decodeEpisodeProfile(encoded: String): Anime4KEpisodeProfile? {
        val fields = encoded.split('|')
        if (fields.size != 2) return null
        val profile = runCatching { Anime4KProfile.valueOf(fields[0]) }.getOrNull() ?: return null
        val customMode = runCatching { Anime4KMode.valueOf(fields[1]) }.getOrNull() ?: return null
        if (profile == Anime4KProfile.Custom && customMode == Anime4KMode.Off) return null
        return Anime4KEpisodeProfile(profile, customMode)
    }

    private fun episodeKey(animeId: Long?, episodeId: Long?): String? {
        if (animeId == null || episodeId == null || animeId <= 0L || episodeId <= 0L) return null
        return Preference.privateKey("anime4k_episode_profile_${animeId}_$episodeId")
    }

    private fun calibrationPreferenceKey(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return Preference.privateKey("anime4k_calibration_$hash")
    }

    // Non-preference

    fun playerStatisticsPage() = preferenceStore.getInt("pref_player_statistics_page", 0)
}
