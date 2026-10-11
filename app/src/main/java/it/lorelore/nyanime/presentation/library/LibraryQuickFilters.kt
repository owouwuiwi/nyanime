package it.lorelore.nyanime.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

@Composable
internal fun LibraryQuickFilters(selected: LibraryQuickFilter, onSelect: (LibraryQuickFilter) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            LibraryQuickFilter.CONTINUE to R.string.library_filter_continue,
            LibraryQuickFilter.NEWS to R.string.library_filter_news,
            LibraryQuickFilter.ALL to R.string.library_filter_all_titles,
        ).forEach { (filter, label) ->
            FilterChip(selected = selected == filter, onClick = {
                onSelect(filter)
            }, label = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) })
        }
    }
}
