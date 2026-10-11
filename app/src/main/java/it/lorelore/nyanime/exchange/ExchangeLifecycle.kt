package it.lorelore.nyanime.exchange

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keep the return to the service dispatcher protected as well as the native close. */
internal suspend fun finishExchangeSession(close: suspend () -> Unit, finished: () -> Unit) {
    withContext(NonCancellable) {
        try {
            withContext(Dispatchers.IO) { close() }
        } finally {
            finished()
        }
    }
}
