package eu.kanade.tachiyomi.benchmark

import android.graphics.Bitmap
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.presentation.discovery.SourceHomeArtwork
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.entries.anime.model.Anime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Only the benchmark APK: a real Home cell, deterministic network images and the real memory cache. */
@Composable
fun ArtworkFluidityProbe() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfers = remember { AtomicInteger() }
    val failLoads = remember { AtomicBoolean(false) }
    var broken by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf(true) }
    var presented by remember { mutableIntStateOf(0) }
    val loader = remember {
        ImageLoader.Builder(context).diskCache(null).components {
            add(object : Keyer<Anime> {
                override fun key(data: Anime, options: Options) = data.thumbnailUrl
            })
            add(object : Fetcher.Factory<Anime> {
                override fun create(data: Anime, options: Options, imageLoader: ImageLoader) = object : Fetcher {
                    override suspend fun fetch(): ImageFetchResult {
                        transfers.incrementAndGet()
                        delay(100)
                        if (failLoads.get()) throw UnsupportedOperationException("Synthetic artwork failure")
                        val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(0xFF227744.toInt())
                        return ImageFetchResult(bitmap.asImage(), false, DataSource.NETWORK)
                    }
                }
            })
        }.build()
    }
    DisposableEffect(loader) { onDispose { loader.shutdown() } }
    val anime = Anime.create().copy(
        id = 123,
        source = 42,
        url = "/series/fixture",
        title = "Synthetic title $revision",
        thumbnailUrl = if (broken) "https://fixture.test/failed.png" else "https://fixture.test/cover.png",
        lastUpdate = revision.toLong(),
    )
    Column(Modifier.padding(24.dp)) {
        Text("ARTWORK_TRANSFERS=${transfers.get()}")
        Text("ARTWORK_PRESENTED=$presented")
        Text("ARTWORK_REVISION=$revision")
        Text("ARTWORK_REFRESH=$refresh")
        if (shown) {
            SourceHomeArtwork(
                anime,
                Modifier.size(180.dp, 260.dp),
                imageLoader = loader,
                refreshKey = refresh,
                onPainterReady = { presented++ },
            )
        }
        Button(onClick = { revision++ }) { Text("Change metadata") }
        Button(onClick = {
            loader.memoryCache?.clear()
            refresh++
        }) { Text("Refresh loaded image") }
        Button(onClick = {
            failLoads.set(true)
            broken = true
        }) { Text("Fail next image") }
        Button(onClick = {
            failLoads.set(false)
            refresh++
        }) { Text("Recover failed image") }
        Button(onClick = {
            scope.launch {
                shown = false
                delay(300)
                shown = true
            }
        }) { Text("Restore cached image") }
    }
}
