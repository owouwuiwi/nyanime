package eu.kanade.tachiyomi.benchmark

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import eu.kanade.presentation.discovery.SourceHomeArtwork
import tachiyomi.domain.entries.anime.model.AnimeCover
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Uses the real Home artwork component with deterministic failed image transfers. */
@Composable
fun ArtworkProbe() {
    val context = LocalContext.current
    var scenario by remember { mutableStateOf("automatico") }
    var refreshKey by remember(scenario) { mutableIntStateOf(0) }
    var attempts by remember(scenario) { mutableIntStateOf(0) }
    var response by remember(scenario) { mutableStateOf("pending") }
    val loader = remember(scenario) {
        val counter = AtomicInteger()
        val failures = if (scenario == "automatico") 1 else 2
        val main = Handler(Looper.getMainLooper())
        ImageLoader.Builder(context).memoryCache(null).diskCache(null).components {
            add(object : Fetcher.Factory<AnimeCover> {
                override fun create(data: AnimeCover, options: Options, imageLoader: ImageLoader) = object : Fetcher {
                    override suspend fun fetch(): FetchResult {
                        val number = counter.incrementAndGet()
                        main.post {
                            attempts = number
                            response = if (number <= failures) "failed" else "ready"
                        }
                        if (number <= failures) throw IOException("Synthetic interrupted image transfer")
                        return ImageFetchResult(ColorImage(0xFF227744.toInt()), false, DataSource.NETWORK)
                    }
                }
            })
        }.build()
    }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    Column(Modifier.padding(24.dp)) {
        Text("ARTWORK_SCENARIO=$scenario")
        Text("ARTWORK_REQUESTS=$attempts")
        Text("ARTWORK_RESPONSE=$response")
        SourceHomeArtwork(
            AnimeCover(123, 42, false, "https://fixture.test/$scenario.jpg", 0),
            Modifier.size(180.dp, 260.dp),
            contentDescription = "Copertina di prova",
            refreshKey = refreshKey,
            imageLoader = loader,
        )
        Button(onClick = { scenario = "manuale" }) { Text("Caso manuale") }
        Button(onClick = { scenario = "refresh" }) { Text("Caso refresh") }
        Button(onClick = { refreshKey++ }) { Text("Aggiorna Home") }
    }
}
