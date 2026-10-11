package eu.kanade.presentation.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.ui.res.stringResource as androidStringResource

/** Dates are ISO civil dates. The extension owns the site's time zone and request format. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceHomeDateSelector(date: String?, onSelect: (String?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val selected = date?.let(LocalDate::parse) ?: LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(
            onClick = { onSelect(selected.minusDays(1).toString()) },
            enabled =
            selected.year > 1900 || selected.dayOfYear > 1,
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, androidStringResource(R.string.home_previous_day))
        }
        TextButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) {
            Text(
                date?.let { selected.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
                    ?: androidStringResource(R.string.home_today),
            )
        }
        if (date != null) TextButton(onClick = { onSelect(null) }) { Text(androidStringResource(R.string.home_today)) }
        IconButton(
            onClick = { onSelect(selected.plusDays(1).toString()) },
            enabled =
            selected < LocalDate.of(2100, 12, 31),
        ) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, androidStringResource(R.string.home_next_day))
        }
    }
    if (picking) {
        val picker =
            rememberDatePickerState(
                initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    picker.selectedDateMillis?.let {
                        onSelect(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    picking = false
                }) { Text(androidStringResource(R.string.home_show)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(androidStringResource(R.string.home_cancel)) }
            },
        ) { DatePicker(picker) }
    }
}
