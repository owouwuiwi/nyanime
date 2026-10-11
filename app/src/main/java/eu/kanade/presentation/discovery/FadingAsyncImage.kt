package eu.kanade.presentation.discovery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.decode.DataSource
import coil3.request.ImageRequest
import coil3.request.crossfade

/** One blend for newly decoded artwork; memory hits never restart an animation while scrolling. */
@Composable
internal fun FadingAsyncImage(
    model: Any,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    previousPainter: Painter? = null,
    reduceMotion: Boolean = false,
    onSuccess: (AsyncImagePainter.State.Success) -> Unit = {},
    onError: (AsyncImagePainter.State.Error) -> Unit = {},
) {
    val context = LocalContext.current
    // The application loader already has a crossfade. This component owns the blend, including
    // the previous artwork, so running both transitions would draw and animate the image twice.
    val request = remember(model, context) {
        (if (model is ImageRequest) model.newBuilder() else ImageRequest.Builder(context).data(model))
            .crossfade(false).build()
    }
    val immediate = reduceMotion || LocalInspectionMode.current
    var previous by remember(model, imageLoader) { mutableStateOf(previousPainter) }
    val opacity = remember(model, imageLoader) { Animatable(if (immediate) 1f else 0f) }
    var ready by remember(model, imageLoader) { mutableStateOf<AsyncImagePainter.State.Success?>(null) }
    var presented by remember(model, imageLoader) { mutableStateOf(false) }
    val success by rememberUpdatedState(onSuccess)
    LaunchedEffect(ready, immediate) {
        val result = ready ?: return@LaunchedEffect
        if (immediate || result.result.dataSource == DataSource.MEMORY_CACHE) {
            opacity.snapTo(1f)
        } else {
            opacity.animateTo(1f, tween(220))
        }
        previous = null
        // A new navigation must retain the image the user actually sees, after the blend completes.
        // Coil may emit the same success again as the parent's painter callback changes. Reduced
        // motion can also restart this effect; neither represents another decoded image.
        if (!presented) {
            presented = true
            success(result)
        }
    }
    Box(modifier) {
        previous?.let {
            Image(it, contentDescription, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        AsyncImage(
            request,
            contentDescription,
            imageLoader,
            Modifier.fillMaxSize().graphicsLayer {
                alpha = if (ready?.result?.dataSource == DataSource.MEMORY_CACHE) 1f else opacity.value
            },
            contentScale = ContentScale.Crop,
            onSuccess = { ready = it },
            onError = onError,
        )
    }
}
