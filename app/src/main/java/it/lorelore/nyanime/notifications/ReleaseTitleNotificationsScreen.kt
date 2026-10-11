package it.lorelore.nyanime.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.entries.components.ItemCover
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.releases.FollowMode
import eu.kanade.tachiyomi.data.releases.ReleaseMedium
import eu.kanade.tachiyomi.data.releases.ReleaseNotifications
import eu.kanade.tachiyomi.data.releases.ReleasePreferences
import eu.kanade.tachiyomi.data.releases.ReleaseReminders
import eu.kanade.tachiyomi.data.releases.ReleaseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold

/** Only local metadata is loaded here; switching an alert never requests a source refresh. */
class ReleaseTitleNotificationsScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val motion = appMotionEnabled()
        val store = remember { ReleaseStore() }
        val titles by remember {
            combine(store.alertTitles(ReleaseMedium.ANIME), store.alertTitles(ReleaseMedium.MANGA)) { anime, manga ->
                (anime + manga).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }
        }.collectAsState(initial = null)
        var query by remember { mutableStateOf("") }
        var medium by remember { mutableStateOf<ReleaseMedium?>(null) }
        var saving by remember { mutableStateOf<Set<String>>(emptySet()) }
        val preferences = remember { ReleasePreferences() }
        val explicitOnly by preferences.explicitAlertsOnly.changes().collectAsState(
            preferences.explicitAlertsOnly.get(),
        )
        val shown = remember(titles, query, medium) {
            titles.orEmpty().filter {
                (medium == null || it.medium == medium) &&
                    it.title.contains(query.trim(), ignoreCase = true)
            }
        }
        Scaffold(topBar = {
            AppBar(title = stringResource(R.string.release_title_notifications), navigateUp = navigator::pop)
        }) { padding ->
            LazyColumn(
                Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Text(
                            stringResource(
                                if (explicitOnly) {
                                    R.string.release_title_notifications_explicit_hint
                                } else {
                                    R.string.release_title_notifications_hint
                                },
                            ),
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        query,
                        { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.release_title_notifications_search)) },
                        shape = MaterialTheme.shapes.large,
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null, ReleaseMedium.ANIME, ReleaseMedium.MANGA).forEach { choice ->
                            FilterChip(
                                selected = medium == choice,
                                onClick = { medium = choice },
                                label = {
                                    Text(
                                        stringResource(
                                            when (choice) {
                                                null -> R.string.release_titles_all
                                                ReleaseMedium.ANIME -> R.string.release_titles_anime
                                                ReleaseMedium.MANGA -> R.string.release_titles_manga
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
                if (titles == null) {
                    item { CircularProgressIndicator() }
                } else if (shown.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.release_title_notifications_empty),
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(shown, key = { it.key }) { item ->
                    val checked =
                        ReleaseAlertPolicy.allows(item.subscription, explicitOnly, reminder = false, item.parent) ||
                            item.medium == ReleaseMedium.ANIME &&
                            ReleaseAlertPolicy.allows(item.subscription, explicitOnly, reminder = true, item.parent)
                    val busy = item.key in saving
                    fun change(enabled: Boolean) {
                        if (busy) return
                        saving = saving + item.key
                        scope.launch {
                            try {
                                withContext(NonCancellable + Dispatchers.IO) {
                                    val current = store.subscription(item.medium, item.id)
                                    store.setSubscription(
                                        item.medium,
                                        item.id,
                                        current.copy(
                                            mode = if (enabled) FollowMode.FOLLOW else current.mode,
                                            availability = enabled,
                                            reminder = enabled,
                                        ),
                                    )
                                    ReleaseNotifications.afterCommit(context)
                                    ReleaseReminders.reschedule(context)
                                }
                            } catch (_: Exception) {
                                android.widget.Toast.makeText(
                                    context,
                                    R.string.release_save_failed,
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } finally {
                                saving = saving - item.key
                            }
                        }
                    }
                    Surface(
                        modifier = if (motion) Modifier.fillMaxWidth().animateItem() else Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Row(
                            Modifier.toggleable(checked, enabled = !busy, role = Role.Switch, onValueChange = ::change)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ItemCover.Book(item.cover, Modifier.width(42.dp), shape = MaterialTheme.shapes.small)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    item.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = when {
                                    !checked &&
                                        explicitOnly &&
                                        item.subscription.mode == FollowMode.AUTO &&
                                        item.parent?.mode != FollowMode.FOLLOW -> {
                                        R.string.release_title_notifications_auto_silent
                                    }
                                    !checked -> R.string.release_title_notifications_muted
                                    item.subscription.mode == FollowMode.FOLLOW -> {
                                        R.string.release_title_notifications_following
                                    }
                                    else -> R.string.release_title_notifications_automatic
                                }
                                Text(
                                    stringResource(subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = checked, enabled = !busy, onCheckedChange = null)
                        }
                    }
                }
            }
        }
    }
}
