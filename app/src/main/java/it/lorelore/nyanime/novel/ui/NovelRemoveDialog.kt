package it.lorelore.nyanime.novel.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import eu.kanade.tachiyomi.R
import it.lorelore.nyanime.novel.NovelBook

/** The same confirmation and data-retention wording on both library views. */
@Composable
internal fun NovelRemoveDialog(book: NovelBook, onDismiss: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title) },
        text = { Text(stringResource(R.string.novel_remove_detail)) },
        confirmButton = {
            TextButton(onRemove) { Text(stringResource(R.string.novel_remove)) }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(stringResource(R.string.novel_cancel)) }
        },
    )
}
