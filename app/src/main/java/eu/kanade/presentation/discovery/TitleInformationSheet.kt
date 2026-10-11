package eu.kanade.presentation.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource as androidStringResource

/** Compact cards retain their complete extension metadata in an accessible information sheet. */
@Composable
internal fun TitleInformationSheet(
    title: String,
    metadata: String?,
    description: String?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (!metadata.isNullOrBlank()) Text(metadata, style = MaterialTheme.typography.bodyMedium)
            if (!description.isNullOrBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    if (!opening) {
                        opening = true
                        scope.launch {
                            try {
                                sheetState.hide()
                                if (!sheetState.isVisible) {
                                    onDismiss()
                                    onOpen()
                                }
                            } finally {
                                // A gesture can interrupt hide(); keep the action usable afterwards.
                                opening = false
                            }
                        }
                    }
                },
                enabled = !opening,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(androidStringResource(R.string.home_open_details)) }
        }
    }
}
