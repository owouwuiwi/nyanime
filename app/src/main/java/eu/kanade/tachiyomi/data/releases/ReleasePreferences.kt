package eu.kanade.tachiyomi.data.releases

import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class ReleasePreferences(private val store: PreferenceStore = Injekt.get()) {
    val enabled = store.getBoolean("release_monitor_enabled", true)
    val reminders = store.getBoolean("release_reminders_enabled", true)
    val advanceReminders = store.getBoolean("release_advance_reminders_enabled", true)
    private val airingReminders = store.getBoolean("release_airing_reminders_enabled", true)
    val availability = store.getBoolean("release_availability_enabled", true)
    val unifiedAgenda = store.getBoolean("release_unified_agenda", true)
    val dismissedAgenda = store.getStringSet("release_dismissed_agenda_v1")

    /** Keep the existing day-before choice for upgrades; all new moments are opt-in. */
    fun reminder(kind: ReleaseReminderKind) = when (kind) {
        ReleaseReminderKind.ADVANCE -> advanceReminders
        ReleaseReminderKind.AIRING -> airingReminders
        else -> store.getBoolean("release_reminder_${kind.name.lowercase(Locale.ROOT)}", false)
    }

    fun selectedReminders(): Set<ReleaseReminderKind> = ReleaseReminderKind.entries
        .filterTo(mutableSetOf()) { reminder(it).get() }
}
