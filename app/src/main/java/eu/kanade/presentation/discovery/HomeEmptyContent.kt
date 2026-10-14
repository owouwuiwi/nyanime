package eu.kanade.presentation.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R

/** No catalogue model or remote requests are created before an installed source is usable. */
@Composable
fun HomeEmptyContent(onStore: () -> Unit) {
    Box(
        Modifier.fillMaxSize().padding(top = LocalHomeContentInset.current)
            .padding(bottom = eu.kanade.tachiyomi.ui.home.LocalFloatingNavigationInset.current)
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 340.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Icon(
                    Icons.Outlined.Explore,
                    null,
                    Modifier.padding(24.dp).size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(R.string.home_no_extensions_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.home_no_extensions_summary),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onStore, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Text(stringResource(R.string.home_no_extensions_store), Modifier.padding(vertical = 8.dp))
            }
        }
    }
}
