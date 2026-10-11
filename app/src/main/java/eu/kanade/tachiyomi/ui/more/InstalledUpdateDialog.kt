package eu.kanade.tachiyomi.ui.more

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.more.UpdateNoteGroups
import eu.kanade.presentation.more.UpdateVersionNotes
import eu.kanade.presentation.more.parseUpdateArchive
import eu.kanade.presentation.theme.NyanimeMark
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun Context.readUpdateArchive(): List<UpdateVersionNotes> =
    resources.openRawResource(R.raw.nyanime_update_history).bufferedReader().use {
        parseUpdateArchive(it.readText())
    }

@Composable
internal fun InstalledUpdateDialog(onContinue: () -> Unit) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME
    val notes by produceState<UpdateVersionNotes?>(null, version) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.readUpdateArchive().firstOrNull { it.version == version } }
                .getOrNull() ?: UpdateVersionNotes(version, null, emptyList())
        }
    }
    val release = notes ?: return

    Dialog(
        onDismissRequest = onContinue,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        val colors = MaterialTheme.colorScheme
        Surface(
            modifier = Modifier.padding(20.dp).widthIn(max = 520.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surfaceContainer,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NyanimeMark(Modifier.size(40.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.app_update_installed_heading),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            Text(
                                text = version,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    if (release.groups.isEmpty()) {
                        Text(
                            text = stringResource(R.string.app_update_installed_no_notes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    } else {
                        UpdateNoteGroups(release, summarize = false)
                    }
                }
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.app_update_installed_continue))
                }
            }
        }
    }
}
