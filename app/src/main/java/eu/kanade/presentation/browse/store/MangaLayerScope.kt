package eu.kanade.presentation.browse.store

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.layer.MangaLayerDeclaration

@Composable
internal fun LayerPresentationBlock(declaration: MangaLayerDeclaration) {
    val presentation = declaration.presentationFor(LocalConfiguration.current.locales[0])
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (presentation == null) {
        Text(stringResource(R.string.store_layer_no_description), color = muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(presentation.summary, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.store_layer_features),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        presentation.features.forEach { feature ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(feature.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(feature.description, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

@Composable
internal fun MangaLayerScope(declaration: MangaLayerDeclaration) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.store_manga_layer_targets), style = MaterialTheme.typography.labelLarge)
        declaration.targets.forEach { target ->
            val capabilities = target.capabilities.sorted().map { capability ->
                stringResource(
                    when (capability) {
                        "home" -> R.string.store_manga_layer_home
                        "catalog" -> R.string.store_manga_layer_catalog
                        "update" -> R.string.store_manga_layer_update
                        "identity" -> R.string.store_manga_layer_identity
                        "reader" -> R.string.store_manga_layer_reader
                        "guide" -> R.string.store_layer_guide
                        "player" -> R.string.store_layer_player
                        "feed" -> R.string.store_layer_feed
                        "article" -> R.string.store_layer_article
                        "search" -> R.string.store_layer_search
                        else -> null
                    } ?: return@map capability,
                )
            }
            Text(target.packageName, style = MaterialTheme.typography.labelMedium)
            Text(capabilities.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
    }
}
