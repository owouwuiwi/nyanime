package eu.kanade.tachiyomi.ui.browse

import kotlinx.coroutines.TimeoutCancellationException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun sourceSearchErrorMessage(error: Throwable): String = when (error) {
    is UnknownHostException -> "Connessione o indirizzo della fonte non disponibile. Controlla la rete e riprova."
    is SocketTimeoutException, is TimeoutCancellationException ->
        "La fonte impiega troppo tempo a rispondere. Puoi riprovare questa ricerca."
    is SSLException -> "Connessione sicura alla fonte non riuscita. Verifica data e ora e aggiorna l’estensione."
    else -> error.message?.takeIf { it.isNotBlank() } ?: "Ricerca non riuscita. Riprova o aggiorna l’estensione."
}
