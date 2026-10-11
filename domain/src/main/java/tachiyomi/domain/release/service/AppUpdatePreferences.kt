package tachiyomi.domain.release.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.UpdateChannel

/** The channel is portable; completing the initial choice belongs to this installation. */
class AppUpdatePreferences(private val store: PreferenceStore) {
    val selection = store.getString("nyanime_update_channel", UpdateChannel.RECOMMENDED.key)
    val choiceComplete = store.getBoolean(Preference.appStateKey("update_channel_choice_complete"), false)

    fun channel() = UpdateChannel.fromKey(selection.get())

    fun confirm(channel: UpdateChannel) {
        if (!choiceComplete.get() || this.channel() != channel) {
            store.getLong(Preference.appStateKey("last_app_check_${channel.key}"), 0).delete()
        }
        selection.set(channel.key)
        choiceComplete.set(true)
    }
}
