package it.lorelore.nyanime.telegram

import nyanime.telegram.TelegramState

/** A verified identity must be fetched again after an authorization refresh, never carried across it. */
internal object TelegramAuthorizationRecovery {
    fun required(state: TelegramState, verifiedAccount: Long?, loggingOut: Boolean): Boolean =
        state.authorization == "disconnected" ||
            (!loggingOut && state.ready && state.account?.id != verifiedAccount)

    fun begin(state: TelegramState): TelegramState =
        state.copy(authorization = "connecting", account = null, failure = null)
}
