package eu.kanade.presentation.more.settings.screen.browse.components.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

@Composable
fun AnimeExtensionStoreDeleteDialog(
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    storeName: String,
    storeIndexUrl: String,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_deleteStore_title))
        },
        text = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_deleteStore_body, storeName, storeIndexUrl))
        },
        confirmButton = {
            TextButton(onClick = {
                onDelete()
                onDismissRequest()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

@Composable
fun AnimeExtensionStoreConfirmDialog(
    onDismissRequest: () -> Unit,
    onCreate: () -> Unit,
    storeIndexUrl: String,
    storeAlreadyExists: Boolean,
    processing: Boolean,
    errorMessage: String?,
) {
    val state = rememberTextFieldState(initialText = storeIndexUrl)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = stringResource(MR.strings.extensionStoresScreen_addStoreDeeplink_bodyText))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    state = state,
                    readOnly = true,
                    supportingText = when {
                        storeAlreadyExists -> {
                            {
                                Text(text = stringResource(MR.strings.extensionStoresScreen_addStore_alreadyExists))
                            }
                        }
                        errorMessage != null -> {
                            {
                                Text(text = errorMessage)
                            }
                        }
                        else -> null
                    },
                    isError = errorMessage != null || storeAlreadyExists,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCreate, enabled = !storeAlreadyExists && !processing) {
                Text(
                    text = stringResource(
                        resource = if (processing) {
                            MR.strings.extensionStoresScreen_addStore_processing
                        } else {
                            MR.strings.action_add
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
