package it.lorelore.nyanime.presentation.themes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.presentation.discovery.FadingAsyncImage
import eu.kanade.presentation.motion.modernMotionEnabled

/** Reserve the image area even while loading; flagged artwork is never requested before reveal. */
@Composable
internal fun ThemeThumbnail(id: String?, hidden: Boolean, network: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (id == null || hidden) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val context = LocalContext.current
            val loader = remember(context) { SingletonImageLoader.get(context) }
            val request = remember(context, id, network) {
                ImageRequest.Builder(context).data("https://i.ytimg.com/vi/$id/mqdefault.jpg")
                    .networkCachePolicy(if (network) CachePolicy.ENABLED else CachePolicy.DISABLED).build()
            }
            var previous by remember { mutableStateOf<Painter?>(null) }
            FadingAsyncImage(
                model = request,
                imageLoader = loader,
                modifier = Modifier.fillMaxSize(),
                previousPainter = previous,
                reduceMotion = !modernMotionEnabled(),
                onSuccess = { previous = it.painter },
            )
        }
    }
}
