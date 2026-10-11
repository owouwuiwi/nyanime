package eu.kanade.presentation.episodeguide

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.episodeguide.EpisodeClassification
import eu.kanade.tachiyomi.data.episodeguide.EpisodeGuide
import eu.kanade.tachiyomi.data.episodeguide.EpisodeGuideRepository
import eu.kanade.tachiyomi.data.episodeguide.GuideBinding
import eu.kanade.tachiyomi.data.episodeguide.GuideCandidate
import eu.kanade.tachiyomi.data.episodeguide.GuideSnapshot
import eu.kanade.tachiyomi.data.workinfo.WorkSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

@Composable
fun guideClassLabel(type: EpisodeClassification): String = stringResource(
    when (type) {
        EpisodeClassification.CANON -> R.string.guide_canon
        EpisodeClassification.ANIME_CANON -> R.string.guide_anime_canon
        EpisodeClassification.MIXED -> R.string.guide_mixed
        EpisodeClassification.FILLER -> R.string.guide_filler
        EpisodeClassification.UNKNOWN -> R.string.guide_unknown
    },
)

@Composable
fun EpisodeGuideEntry(anime: Anime, episodes: List<Episode>, metadata: WorkSummary? = null) {
    val context = LocalContext.current
    val repository = remember { EpisodeGuideRepository.get(context) }
    val states by repository.states.collectAsState()
    val snapshot = states[repository.key(anime)] ?: GuideSnapshot()
    var open by rememberSaveable(anime.id) { mutableStateOf(false) }
    LaunchedEffect(anime.id, metadata) {
        val identity = repository.identity(anime)
        repository.refresh(
            anime,
            metadata?.let {
                identity.copy(
                    aliases = (identity.aliases + it.aliases + it.title).distinct(),
                    year = it.year ?: identity.year,
                    format = it.format ?: identity.format,
                    season = it.season ?: identity.season,
                    part = it.part ?: identity.part,
                    malId = it.malId ?: identity.malId,
                )
            } ?: identity,
        )
    }
    val count = remember(episodes, snapshot) {
        episodes.count {
            repository.classification(anime, it.url, it.episodeNumber, it.fillermark) ==
                EpisodeClassification.FILLER
        }
    }
    val motion = modernMotionEnabled()
    Surface(
        onClick = { open = true },
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.titleSmall)
                // A stable secondary line avoids shifting the episode list while loading.
                AnimatedContent(
                    targetState = when {
                        count > 0 -> context.getString(R.string.guide_count, count)
                        snapshot.loading -> context.getString(R.string.guide_loading)
                        snapshot.guide != null -> context.getString(R.string.guide_ready)
                        else -> context.getString(R.string.guide_optional)
                    },
                    transitionSpec = { ModernMotion.transform(motion) },
                    label = "guide-summary",
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
    if (open) EpisodeGuideSheet(anime, episodes, onDismiss = { open = false })
}

@Composable
fun EpisodeGuideBadge(anime: Anime, episode: Episode, type: EpisodeClassification) {
    if (type !in listOf(EpisodeClassification.FILLER, EpisodeClassification.MIXED)) return
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            guideClassLabel(type),
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
    if (open) EpisodeGuideSheet(anime, listOf(episode), episode, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EpisodeGuideSheet(anime: Anime, episodes: List<Episode>, selected: Episode? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { EpisodeGuideRepository.get(context) }
    val states by repository.states.collectAsState()
    val state = states[repository.key(anime)] ?: GuideSnapshot()
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.current
    var choosing by remember { mutableStateOf(false) }
    var searchTitle by rememberSaveable(anime.id) { mutableStateOf(anime.title) }
    var candidate by remember { mutableStateOf<Pair<String, GuideCandidate>?>(null) }
    var draft by remember { mutableStateOf<EpisodeGuide?>(null) }
    var offset by remember { mutableStateOf(state.binding?.offset?.toString() ?: "0") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun searchForGuide() {
        if (searchTitle.isBlank() || state.loading) return
        choosing = true
        scope.launch {
            repository.refresh(
                anime,
                work = repository.identity(anime).copy(title = searchTitle.trim(), aliases = emptyList()),
                search = true,
            )
        }
    }
    fun select(value: Pair<String, GuideCandidate>) {
        previewJob?.cancel()
        candidate = value
        draft = null
        busy = true
        error = false
        previewJob = scope.launch {
            try {
                val result = repository.preview(value.first, value.second.reference)
                if (candidate == value) draft = result
            } catch (
                cancel: CancellationException,
            ) {
                throw cancel
            } catch (_: Exception) {
                if (candidate == value) error = true
            } finally {
                if (candidate == value) busy = false
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.guide_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    anime.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!repository.available()) {
                item {
                    Text(stringResource(R.string.guide_install))
                    Button(onClick = {
                        onDismiss()
                        navigator?.push(GuideStoreScreen())
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.guide_store))
                    }
                }
            } else if (candidate != null) {
                item {
                    Text(candidate!!.second.title, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.guide_confirm_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = offset,
                        onValueChange = { offset = it.take(7) },
                        label = { Text(stringResource(R.string.guide_offset)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = offset.toIntOrNull()?.let { it !in -100_000..100_000 } != false,
                    )
                    val shift = offset.toIntOrNull()
                    episodes.sortedBy { it.episodeNumber }.filter {
                        it.episodeNumber >= 1 &&
                            it.episodeNumber % 1.0 == 0.0
                    }.take(3).forEach {
                        Text(
                            stringResource(
                                R.string.guide_mapping,
                                it.episodeNumber.toInt(),
                                it.episodeNumber.toInt() + (shift ?: 0),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    draft?.let {
                        Text(
                            stringResource(
                                R.string.guide_coverage,
                                it.episodes.minOfOrNull { row -> row.number } ?: 0,
                                it.episodes.maxOfOrNull { row ->
                                    row.number
                                } ?: 0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    if (error) Text(stringResource(R.string.guide_unavailable), color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            previewJob?.cancel()
                            candidate = null
                            draft = null
                            error = false
                        }) { Text(stringResource(R.string.guide_back)) }
                        Button(
                            enabled = !busy &&
                                draft != null &&
                                shift != null &&
                                shift in -100_000..100_000 &&
                                episodes.any { (it.episodeNumber + shift) in 1.0..100_000.0 },
                            onClick = {
                                val value = candidate!!
                                repository.associate(anime, GuideBinding(value.first, value.second.reference, shift!!))
                                candidate = null
                                draft = null
                                choosing = false
                                scope.launch { repository.refresh(anime) }
                            },
                        ) { Text(stringResource(R.string.guide_confirm)) }
                    }
                }
            } else {
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.unavailable) {
                    item {
                        Text(stringResource(R.string.guide_unavailable), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.excluded) item { Text(stringResource(R.string.guide_removed)) }
                if (choosing || state.guide == null) {
                    item {
                        Text(stringResource(R.string.guide_choose_hint), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = searchTitle,
                            onValueChange = { searchTitle = it.take(256) },
                            label = { Text(stringResource(R.string.guide_find)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { searchForGuide() }),
                        )
                        Button(onClick = { searchForGuide() }, enabled = searchTitle.isNotBlank() && !state.loading) {
                            Text(stringResource(R.string.guide_find))
                        }
                    }
                    items(state.candidates, key = { it.first + it.second.reference }) { value ->
                        Surface(onClick = {
                            select(value)
                        }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text(value.second.title, style = MaterialTheme.typography.titleSmall)
                                value.second.year?.let {
                                    Text(it.toString(), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                if (!choosing && state.guide != null) {
                    item {
                        OutlinedButton(onClick = {
                            choosing = true
                            scope.launch { repository.refresh(anime, search = true) }
                        }, enabled = !state.loading) {
                            Text(stringResource(R.string.guide_correct))
                        }
                    }
                }
                state.guide?.let { guide ->
                    item {
                        Text(guide.work.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.guide_attribution, guide.attribution),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        guide.updated?.let {
                            Text(stringResource(R.string.guide_updated, it), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            stringResource(R.string.guide_incomplete),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                offset = state.binding?.offset?.toString() ?: "0"
                                candidate = state.binding?.provider?.let { it to guide.work }
                                draft = guide
                            }) { Text(stringResource(R.string.guide_numbers)) }
                            TextButton(onClick = {
                                repository.associate(anime, GuideBinding())
                            }) { Text(stringResource(R.string.guide_remove)) }
                        }
                    }
                }
                if (state.guide == null && state.binding?.reference != null) {
                    item {
                        TextButton(onClick = { repository.associate(anime, GuideBinding()) }) {
                            Text(stringResource(R.string.guide_remove))
                        }
                    }
                }
                if (selected != null) {
                    item {
                        val type = repository.classification(
                            anime,
                            selected.url,
                            selected.episodeNumber,
                            selected.fillermark,
                        )
                        Text(selected.name, style = MaterialTheme.typography.titleSmall)
                        Text(guideClassLabel(type), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                when (type) {
                                    EpisodeClassification.FILLER -> R.string.guide_filler_meaning
                                    EpisodeClassification.MIXED -> R.string.guide_mixed_meaning
                                    else -> R.string.guide_other_meaning
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                repository.setOverride(anime, selected.url, true)
                            }) { Text(stringResource(R.string.guide_mark_filler)) }
                            TextButton(onClick = {
                                repository.setOverride(anime, selected.url, false)
                            }) { Text(stringResource(R.string.guide_mark_canon)) }
                            TextButton(onClick = {
                                repository.setOverride(anime, selected.url, null)
                            }) { Text(stringResource(R.string.guide_reset)) }
                        }
                    }
                } else {
                    item {
                        GuideSwitch(stringResource(R.string.guide_hide), repository.hidden(anime)) {
                            repository.setHidden(anime, it)
                        }
                        EpisodeGuidePlayerPreferences(anime)
                    }
                    items(episodes.sortedBy { it.episodeNumber }, key = { it.id }) { episode ->
                        val type = repository.classification(
                            anime,
                            episode.url,
                            episode.episodeNumber,
                            episode.fillermark,
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                episode.episodeNumber.toString().removeSuffix(".0"),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(episode.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                guideClassLabel(type),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = change)
    }
}

@Composable
fun EpisodeGuideHidePreference(anime: Anime) {
    val context = LocalContext.current
    val repository = remember { EpisodeGuideRepository.get(context) }
    val states by repository.states.collectAsState()
    states[repository.key(anime)]
    GuideSwitch(stringResource(R.string.guide_hide), repository.hidden(anime)) { repository.setHidden(anime, it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpisodeGuidePlayerPreferences(anime: Anime?) {
    val context = LocalContext.current
    val repository = remember { EpisodeGuideRepository.get(context) }
    val states by repository.states.collectAsState()
    val global by repository.preferences.skip().collectPreferenceAsState()
    Text(stringResource(R.string.guide_skip), style = MaterialTheme.typography.titleMedium)
    GuideSwitch(stringResource(R.string.guide_global), global) {
        repository.preferences.skip().set(it)
        anime?.let(repository::changed)
    }
    if (anime != null) {
        val override = repository.skipOverride(anime)
        // The state revision makes transient incognito choices observable as well.
        states[repository.key(anime)]
        Text(stringResource(R.string.player_for_this_anime), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                -1 to R.string.player_use_global,
                1 to R.string.guide_on,
                0 to R.string.guide_off,
            ).forEach { (value, label) ->
                FilterChip(selected = override == value, onClick = {
                    repository.setSkipOverride(anime, value)
                }, label = { Text(stringResource(label)) })
            }
        }
    }
    Text(
        stringResource(R.string.guide_skip_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
