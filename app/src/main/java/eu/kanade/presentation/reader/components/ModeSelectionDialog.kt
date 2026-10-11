package eu.kanade.presentation.reader.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.R
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ModeSelectionDialog(
    onApply: () -> Unit,
    onUseDefault: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.padding(vertical = 16.dp)) {
        Column {
            content()

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsItemsPaddings.Horizontal),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                onUseDefault?.let {
                    OutlinedButton(onClick = it) {
                        Text(text = androidx.compose.ui.res.stringResource(R.string.reading_mode_use_default))
                    }
                }

                FilledTonalButton(
                    onClick = onApply,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                        )
                        Text(text = stringResource(MR.strings.action_apply), maxLines = 1)
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun Preview() {
    TachiyomiPreviewTheme {
        Surface {
            Column {
                ModeSelectionDialog(
                    onApply = {},
                    onUseDefault = {},
                ) {
                    Text("Dummy content")
                }

                ModeSelectionDialog(
                    onApply = {},
                ) {
                    Text("Dummy content without default")
                }
            }
        }
    }
}
