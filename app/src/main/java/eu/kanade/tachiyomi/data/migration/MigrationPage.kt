package eu.kanade.tachiyomi.data.migration

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

@Composable
internal fun MigrationPage(
    step: Int,
    title: String,
    body: String,
    completed: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val accent = if (dark) Color(0xFFE5223E) else Color(0xFFC84C16)
    MaterialTheme(
        colorScheme = if (dark) {
            darkColorScheme(primary = accent, onPrimary = Color.White, secondary = accent, onSecondary = Color.White)
        } else {
            lightColorScheme(primary = accent, onPrimary = Color.White, secondary = accent, onSecondary = Color.White)
        },
    ) {
        Surface(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(accent.copy(alpha = 0.12f), Color.Transparent)),
                ),
            ) {
                Column(
                    Modifier.align(
                        Alignment.TopCenter,
                    ).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight().safeDrawingPadding()
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Image(
                        painterResource(
                            if (dark) R.drawable.nyanime_wordmark_dark else R.drawable.nyanime_wordmark_light,
                        ),
                        null,
                        Modifier.height(36.dp).align(Alignment.Start),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            R.string.migration_prepare,
                            R.string.migration_transfer,
                            R.string.migration_verify,
                        ).forEachIndexed {
                                index,
                                label,
                            ->
                            Surface(
                                Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp),
                                color = if (completed || index == step) {
                                    accent.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (completed || index < step) {
                                        Icon(Icons.Default.Check, stringResource(label), tint = accent)
                                    } else {
                                        Text(
                                            "${index + 1}",
                                            color = if (index <=
                                                step
                                            ) {
                                                accent
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(
                            stringResource(R.string.migration_keeps_original),
                            Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = actions,
                    )
                }
            }
        }
    }
}
