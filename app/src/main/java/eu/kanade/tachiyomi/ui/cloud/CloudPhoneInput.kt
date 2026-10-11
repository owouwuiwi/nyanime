package eu.kanade.tachiyomi.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import nyanime.telegram.TelegramPhoneCountry
import nyanime.telegram.TelegramPhoneNumbers
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

@Composable
internal fun CloudPhoneInput(
    value: String,
    region: String,
    enabled: Boolean,
    isError: Boolean,
    onChange: (String) -> Unit,
    onRegionChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val locale = LocalConfiguration.current.locales[0]
    val country = remember(region) { TelegramPhoneNumbers.countries.first { it.region == region } }
    val name = remember(region, locale) { Locale("", region).getDisplayCountry(locale) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.cloud_phone_country), style = MaterialTheme.typography.labelLarge)
        Surface(
            onClick = {
                focusManager.clearFocus()
                choosing = true
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { role = Role.Button },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(countryFlag(region), style = MaterialTheme.typography.headlineSmall)
                Text(name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "+${country.callingCode}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(20.dp))
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            isError = isError,
            label = { Text(stringResource(R.string.cloud_phone)) },
            placeholder = { Text(remember(region) { TelegramPhoneNumbers.example(region) }) },
            leadingIcon = { Icon(Icons.Outlined.PhoneIphone, null) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
        Text(
            stringResource(R.string.cloud_phone_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (choosing && enabled) {
        CloudPhoneCountryPicker(region, onDismiss = { choosing = false }) {
            onRegionChange(it)
            choosing = false
        }
    }
}

@Composable
private fun CloudPhoneCountryPicker(region: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val config = LocalConfiguration.current
    val locale = config.locales[0]
    var query by remember { mutableStateOf("") }
    val countries = remember(locale) {
        val collator = Collator.getInstance(locale)
        TelegramPhoneNumbers.countries.sortedWith { first, second ->
            collator.compare(
                Locale("", first.region).getDisplayCountry(locale),
                Locale("", second.region).getDisplayCountry(locale),
            )
        }
    }
    val filtered = remember(query, countries, locale) {
        val search = foldCountrySearch(query.trim().removePrefix("+"))
        countries.filter {
            search.isEmpty() ||
                foldCountrySearch(Locale("", it.region).getDisplayCountry(locale)).contains(search) ||
                foldCountrySearch(Locale("", it.region).getDisplayCountry(Locale.ENGLISH)).contains(search) ||
                it.region.lowercase(Locale.ROOT).contains(search) ||
                it.callingCode.toString().startsWith(search)
        }
    }
    CloudAdaptiveSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().heightIn(max = config.screenHeightDp.dp * 0.86f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.cloud_phone_country),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.cloud_cancel)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { if (it.length <= 80) query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                placeholder = { Text(stringResource(R.string.cloud_phone_country_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (filtered.isEmpty()) {
                    item { Text(stringResource(R.string.cloud_phone_country_empty), Modifier.padding(24.dp)) }
                }
                items(filtered, key = { it.region }) { country ->
                    CountryRow(country, locale, selected = country.region == region) { onSelect(country.region) }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun CountryRow(country: TelegramPhoneCountry, locale: Locale, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().semantics {
            role = Role.RadioButton
            this.selected = selected
        },
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(countryFlag(country.region), style = MaterialTheme.typography.titleLarge)
            Text(Locale("", country.region).getDisplayCountry(locale), Modifier.weight(1f))
            Text("+${country.callingCode}", style = MaterialTheme.typography.labelLarge)
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp)) else Spacer(Modifier.width(20.dp))
        }
    }
}

private fun countryFlag(region: String): String = region.flatMap {
    Character.toChars(0x1F1E6 + it.code - 'A'.code).toList()
}.joinToString("")

private fun foldCountrySearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
