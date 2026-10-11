package nyanime.telegram

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class TelegramAccountSessionState(val account: Long?, val revision: String?, val version: Long)

/** Authentication evidence only. Reading this ledger never starts the Telegram engine. */
class TelegramAccountSessions private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("telegram.account-session.v1", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        TelegramAccountSessionState(
            preferences.getLong("account", 0).takeIf { it > 0 },
            preferences.getString("revision", null),
            preferences.getLong("version", 0),
        ).let { if (it.revision.isNullOrEmpty()) it.copy(account = null, revision = null) else it },
    )
    val state = mutableState.asStateFlow()

    @Synchronized
    fun signedIn(account: Long) {
        require(account > 0)
        val previous = state.value
        if (previous.account == account && previous.revision != null) return
        save(TelegramAccountSessionState(account, UUID.randomUUID().toString(), previous.version + 1))
    }

    @Synchronized
    fun invalidate() {
        val previous = state.value
        if (previous.account == null && previous.revision == null) return
        save(TelegramAccountSessionState(null, null, previous.version + 1))
    }

    private fun save(next: TelegramAccountSessionState) {
        // Persist before publication: missed UI events or process death cannot revive an old grant.
        check(
            preferences.edit().putLong("account", next.account ?: 0)
                .putString("revision", next.revision).putLong("version", next.version).commit(),
        )
        mutableState.value = next
    }

    companion object {
        @Volatile private var instance: TelegramAccountSessions? = null
        fun get(context: Context): TelegramAccountSessions = instance ?: synchronized(this) {
            instance ?: TelegramAccountSessions(context.applicationContext).also { instance = it }
        }

        fun requiresLogin(authorization: String): Boolean = authorization in setOf(
            "authorizationStateWaitPhoneNumber",
            "authorizationStateWaitCode",
            "authorizationStateWaitPassword",
            "authorizationStateWaitEmailAddress",
            "authorizationStateWaitEmailCode",
            "authorizationStateWaitRegistration",
            "authorizationStateWaitOtherDeviceConfirmation",
            "authorizationStateLoggingOut",
        )
    }
}
