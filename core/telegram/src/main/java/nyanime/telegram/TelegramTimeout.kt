package nyanime.telegram

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Only this operation's deadline becomes a retryable failure; caller cancellation still propagates. */
suspend fun <T : Any> withTelegramTimeout(timeoutMillis: Long, block: suspend CoroutineScope.() -> T): T =
    withTimeoutOrNull(timeoutMillis, block)
        ?: throw TelegramException(TelegramFailure.DISCONNECTED, code = 408)
