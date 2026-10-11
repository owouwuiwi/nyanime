package eu.kanade.presentation.workinfo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.workinfo.WorkCredit
import eu.kanade.tachiyomi.data.workinfo.WorkInformation
import eu.kanade.tachiyomi.data.workinfo.WorkSummary
import java.util.Locale

@Composable
internal fun WorkInformationSection(state: WorkInfoState, hasSourceStatus: Boolean = false) {
    if (!state.enabled) return
    val motion = modernMotionEnabled()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.work_info_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                state.information?.work?.reference?.provider?.label?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { state.choosing = true }) {
                Icon(
                    if (state.information == null) Icons.Outlined.ManageSearch else Icons.Outlined.Edit,
                    contentDescription = stringResource(
                        if (state.information == null) R.string.work_info_search else R.string.work_info_correct,
                    ),
                )
            }
        }
        if (state.loading && state.information == null) WorkInfoSkeleton()
        if (state.failed || state.resolution.partialFailure) {
            WorkInfoNotice(
                stringResource(if (!state.network) R.string.work_info_offline else R.string.work_info_error),
                retry = if (state.network && !state.loading) {
                    { state.revision++ }
                } else {
                    null
                },
            )
        }
        if (!state.loading && state.information == null && !state.failed) {
            WorkPanel(Icons.Outlined.Info, stringResource(R.string.work_info_none)) {
                Text(
                    stringResource(if (state.network) R.string.work_info_none_hint else R.string.work_info_offline),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { state.choosing = true }, enabled = state.network) {
                    Text(stringResource(R.string.work_info_choose))
                }
            }
        }
        AnimatedVisibility(
            visible = state.information != null,
            enter = if (motion) ModernMotion.enter() else EnterTransition.None,
            exit = if (motion) ModernMotion.exit() else ExitTransition.None,
        ) {
            state.information?.let {
                val display = eu.kanade.tachiyomi.data.workinfo.WorkInformationPresentation.details(
                    it,
                    state.identity,
                    hasSourceStatus,
                )
                WorkInformationBody(display, state.incognito, showSynopsis = false, language = state.language)
            }
        }
    }
    if (state.choosing) WorkAssociationSheet(state)
}

@Composable
internal fun WorkInformationBody(
    information: WorkInformation,
    incognito: Boolean,
    showSynopsis: Boolean,
    language: String,
) {
    val navigator = LocalNavigator.currentOrThrow
    val uri = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (showSynopsis && !information.synopsis.isNullOrBlank()) {
            WorkPanel(Icons.Outlined.AutoStories, stringResource(R.string.work_info_story)) {
                ExpandableWorkText(information.synopsis)
                WorkLanguageNote(information)
            }
        }
        WorkPanel(Icons.Outlined.Info, stringResource(R.string.work_info_details)) {
            WorkFact(stringResource(R.string.work_info_original), information.originalTitle)
            val aliases = information.work.aliases.filterNot {
                it == information.work.title ||
                    it == information.originalTitle
            }
            if (aliases.isNotEmpty()) {
                var expanded by remember(information.work.reference) { mutableStateOf(false) }
                WorkFact(
                    stringResource(R.string.work_info_aliases),
                    (if (expanded) aliases else aliases.take(3)).joinToString(" · "),
                )
                if (aliases.size > 3 &&
                    !expanded
                ) {
                    TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.work_info_all)) }
                }
            }
            val format = information.work.format?.let { workLabel(it) }
            val year = information.work.year?.toString()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { WorkFact(stringResource(R.string.work_info_year), year) }
                Column(Modifier.weight(1f)) { WorkFact(stringResource(R.string.work_info_format), format) }
            }
            WorkFact(stringResource(R.string.work_info_status), information.status?.let { workLabel(it) })
            WorkFact(
                stringResource(R.string.work_info_country),
                information.countries.joinToString(" · ") {
                    if (it.length == 2) Locale("", it).getDisplayCountry(Locale(language)) else it
                },
            )
            WorkFact(
                stringResource(R.string.work_info_language),
                information.languages.map { workLanguage(it) }.joinToString(" · "),
            )
            WorkFact(
                stringResource(R.string.work_info_runtime),
                information.minutes?.let {
                    stringResource(R.string.work_info_minutes, it)
                },
            )
            WorkFact(stringResource(R.string.work_info_studios), information.studios.joinToString(" · "))
            WorkFact(stringResource(R.string.work_info_collection), information.collections.joinToString(" · "))
            WorkFact(stringResource(R.string.work_info_based_on), information.adaptedFrom.joinToString(" · "))
        }
        val ref = information.work.reference
        if (information.creators.isNotEmpty()) {
            WorkPanel(Icons.Outlined.Movie, stringResource(R.string.work_info_creators)) {
                information.creators.take(4).forEach { WorkCreditRow(it, incognito) }
                if (information.creators.size > 4 || information.moreCreators) {
                    TextButton(onClick = {
                        navigator.push(WorkCreditsScreen(ref.provider.name, ref.id, ref.kind.name, false, incognito))
                    }) {
                        Text(stringResource(R.string.work_info_all))
                    }
                }
            }
        }
        if (information.cast.isNotEmpty()) {
            WorkPanel(Icons.Outlined.Groups, stringResource(R.string.work_info_cast)) {
                val preferred = if (language == "it") "Italian" else "English"
                information.cast.sortedBy {
                    if (it.language ==
                        preferred
                    ) {
                        0
                    } else if (it.language == "Japanese") {
                        1
                    } else {
                        2
                    }
                }
                    .take(4).forEach { WorkCreditRow(it, incognito) }
                if (information.cast.size > 4 || information.moreCast) {
                    TextButton(onClick = {
                        navigator.push(WorkCreditsScreen(ref.provider.name, ref.id, ref.kind.name, true, incognito))
                    }) {
                        Text(stringResource(R.string.work_info_all))
                    }
                }
            }
        }
        if (information.seasons.isNotEmpty() ||
            information.episodes != null ||
            information.chapters != null ||
            information.volumes != null
        ) {
            WorkPanel(Icons.Outlined.AutoStories, stringResource(R.string.work_info_structure)) {
                Text(
                    stringResource(R.string.work_info_structure_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOfNotNull(
                    information.episodes?.let { stringResource(R.string.work_info_episodes, it) },
                    information.chapters?.let { stringResource(R.string.work_info_chapters, it) },
                    information.volumes?.let { stringResource(R.string.work_info_volumes, it) },
                ).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), fontWeight = FontWeight.SemiBold) }
                var allSeasons by remember(ref) { mutableStateOf(false) }
                (if (allSeasons) information.seasons else information.seasons.take(4)).forEach { season ->
                    WorkFact(
                        stringResource(R.string.work_info_season, season.number),
                        listOfNotNull(
                            season.year?.toString(),
                            season.episodes?.let {
                                stringResource(R.string.work_info_episodes, it)
                            },
                        ).joinToString(" · "),
                    )
                }
                if (information.seasons.size > 4 &&
                    !allSeasons
                ) {
                    TextButton(onClick = { allSeasons = true }) { Text(stringResource(R.string.work_info_all)) }
                }
            }
        }
        if (information.relations.isNotEmpty()) {
            WorkPanel(Icons.Outlined.AutoStories, stringResource(R.string.work_info_relations)) {
                var allRelations by remember(ref) { mutableStateOf(false) }
                (if (allRelations) information.relations else information.relations.take(4)).forEach { relation ->
                    WorkSummaryRow(relation.work, workLabel(relation.relation)) {
                        val related = relation.work.reference
                        navigator.push(
                            WorkInformationScreen(related.provider.name, related.id, related.kind.name, incognito),
                        )
                    }
                }
                if (information.relations.size > 4 &&
                    !allRelations
                ) {
                    TextButton(onClick = { allRelations = true }) { Text(stringResource(R.string.work_info_all)) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column {
            TextButton(onClick = {
                uri.openUri(ref.url)
            }) { Text(stringResource(R.string.work_info_catalogue, ref.provider.label)) }
            information.imdbUrl?.let { url ->
                TextButton(onClick = { uri.openUri(url) }) { Text(stringResource(R.string.work_info_imdb)) }
            }
            val attribution = when (ref.provider.name) {
                "TVMAZE" -> R.string.work_info_tvmaze_credit
                "WIKIDATA" -> R.string.work_info_wikidata_credit
                else -> R.string.work_info_anilist_credit
            }
            Text(
                stringResource(attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun WorkPanel(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
internal fun WorkFact(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun WorkCreditRow(credit: WorkCredit, incognito: Boolean) {
    val navigator = LocalNavigator.currentOrThrow
    val role = listOfNotNull(
        credit.character,
        workLabel(credit.role).takeIf(String::isNotBlank),
        credit.language?.let { workLanguage(it) },
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = credit.person?.isValid() == true) {
            credit.person?.takeIf {
                it.isValid()
            }?.let { navigator.push(WorkPersonScreen(it.provider.name, it.id, incognito)) }
        }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                credit.name.take(1),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(credit.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (credit.person != null) Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
    }
}

@Composable
internal fun WorkSummaryRow(work: WorkSummary, relation: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(
            RoundedCornerShape(12.dp),
        ).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!relation.isNullOrBlank()) {
                Text(
                    relation,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                work.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    work.year?.toString(),
                    work.format?.let {
                        workLabel(it)
                    },
                    work.reference.provider.label,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
    }
}

@Composable
internal fun WorkLanguageNote(information: WorkInformation) {
    Text(
        stringResource(
            R.string.work_info_language_note,
            information.work.reference.provider.label,
            Locale(information.textLanguage).displayLanguage,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ExpandableWorkText(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 7,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = {
            overflowing =
                it.hasVisualOverflow
        },
    )
    if (!expanded &&
        overflowing
    ) {
        TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.work_info_all)) }
    }
}

@Composable
internal fun WorkInfoSkeleton() {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.work_info_loading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(0.7f, 1f, 0.85f).forEach { width ->
            Box(
                Modifier.fillMaxWidth(
                    width,
                ).height(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)),
            )
        }
    }
}

@Composable
internal fun WorkInfoNotice(message: String, retry: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) TextButton(onClick = retry) { Text(stringResource(R.string.work_info_retry)) }
    }
}
