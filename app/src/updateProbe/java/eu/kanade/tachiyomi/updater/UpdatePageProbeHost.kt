package eu.kanade.tachiyomi.updater

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/** Only compiled with -PupdateProbe=true; no exported Activity or production entry point. */
object UpdatePageProbeHost {
    @JvmStatic
    fun show(activity: Activity, version: String, url: String, dark: Boolean) {
        (activity as ComponentActivity).setContent {
            TachiyomiPreviewTheme(darkTheme = dark) {
                Navigator(listOf(ProbeRoot, NewUpdateScreen(version, NOTES, "https://example.invalid/release", url))) {
                    CurrentScreen()
                }
            }
        }
    }

    @JvmStatic
    fun cleanup(context: Context, url: String) {
        val matching = runBlocking {
            AppUpdateDownloadJob.observe(context).first().filter { AppUpdateDownloadJob.updateTag(url) in it.tags }
        }
        if (matching.isEmpty()) return
        AppUpdateDownloadJob.stop(context)
        for (work in matching) {
            val path = work.outputData.getString(AppUpdateDownloadJob.OUTPUT_APK_PATH) ?: continue
            val file = File(path)
            val directory = context.externalCacheDir ?: context.cacheDir
            check(file.canonicalFile.parentFile == directory.canonicalFile)
            check(file.name == "update-${work.id}.apk")
            file.delete()
        }
    }

    private object ProbeRoot : Screen {
        @Composable
        override fun Content() {
            eu.kanade.tachiyomi.ui.more.ReadyAppUpdateSurface(allowDismiss = false)
        }
    }

    private val NOTES = """
        <!-- nyanime-changelog:1 -->
        ### 0.99.0.0: Aggiornamenti più leggibili
        #### Novità
        - Una nuova schermata con tutte le versioni da recuperare.
        - Novità, correzioni e prestazioni hanno sezioni distinte.
        - Lo storico completo è disponibile anche senza connessione.
        - Le note lunghe si possono espandere senza perdere il punto.
        - Questo quinto elemento compare con **Mostra tutte**.
        #### Correzioni
        - Il download continua anche quando lasci questa schermata.
        - Puoi riprendere un’installazione annullata.
        #### Prestazioni
        - I dettagli precedenti vengono caricati in background.
        ### 0.98.0.0: Una versione precedente
        #### Novità
        - Questo passaggio intermedio resta raggiungibile.
        #### Correzioni
        - I testi grandi rimangono leggibili anche su schermi stretti.
        #### Prestazioni
        - La cronologia usa una lista virtualizzata.
    """.trimIndent()
}
