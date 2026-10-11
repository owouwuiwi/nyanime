package nyanime.telegram

import kotlinx.serialization.json.JsonObject

/**
 * TDLib emits connection changes, not an initial state for each IPC subscriber.
 * Snapshot and live delivery share a lock so a late subscriber never sees an old state last.
 * Only connection metadata is retained; authorization and messages are not cached here.
 */
class TelegramConnectionReplay {
    private var latest: JsonObject? = null

    fun attach(deliver: (JsonObject?) -> Unit) = synchronized(this) {
        deliver(latest)
    }

    fun update(event: JsonObject, deliver: () -> Unit) = synchronized(this) {
        require(event.type() == "updateConnectionState")
        val state = requireNotNull(event.objectOrNull("state"))
        require(state.type().startsWith("connectionState"))
        latest = tdRequest("updateConnectionState", "state" to tdRequest(state.type()))
        deliver()
    }
}
