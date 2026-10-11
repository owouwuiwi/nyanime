package it.lorelore.nyanime.studio

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import eu.kanade.tachiyomi.R

/** Fixed-size targets stay visible on phones; labels remain available to touch and accessibility. */
@Composable internal fun StudioIconAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick, Modifier.semantics { selected = isSelected }, enabled = enabled) {
            Icon(icon, label, tint = if (isSelected) MaterialTheme.colorScheme.primary else LocalContentColor.current)
        }
    }
}

/** Friendly names never change graph keys or values authored by the user. */
@Composable internal fun studioTerm(key: String): String {
    val resource = when (key) {
        "single" -> R.string.studio_term_single
        "multiple" -> R.string.studio_term_multiple
        "text" -> R.string.studio_term_text
        "posters" -> R.string.studio_term_posters
        "featured" -> R.string.studio_term_featured
        "ranking" -> R.string.studio_term_ranking
        "chapters" -> R.string.studio_term_chapters
        "updates" -> R.string.studio_term_updates
        "title" -> R.string.studio_term_title
        "url" -> R.string.studio_term_url
        "cover" -> R.string.studio_term_cover
        "aliases" -> R.string.studio_term_aliases
        "background" -> R.string.studio_term_background
        "description" -> R.string.studio_term_description
        "genres" -> R.string.studio_term_genres
        "author" -> R.string.studio_term_author
        "artist" -> R.string.studio_term_artist
        "status" -> R.string.studio_term_status
        "number" -> R.string.studio_term_number
        "uploaded" -> R.string.studio_term_uploaded
        "group" -> R.string.studio_term_group
        "resolution" -> R.string.studio_term_resolution
        "subtitles" -> R.string.studio_term_subtitles
        "audio" -> R.string.studio_term_audio
        "css" -> R.string.studio_term_css
        "xpath" -> R.string.studio_term_xpath
        "json" -> R.string.studio_term_json
        "constant" -> R.string.studio_term_constant
        "value" -> R.string.studio_term_value
        "pattern" -> R.string.studio_term_pattern
        "replacement" -> R.string.studio_term_replacement
        "format" -> R.string.studio_term_format
        "prefix" -> R.string.studio_term_prefix
        "suffix" -> R.string.studio_term_suffix
        "wrap" -> R.string.studio_term_wrap
        "reverse" -> R.string.studio_term_reverse
        "unique" -> R.string.studio_term_unique
        "take" -> R.string.studio_term_take
        "flatten" -> R.string.studio_term_flatten
        "exists" -> R.string.studio_term_exists
        "equals" -> R.string.studio_term_equals
        "contains" -> R.string.studio_term_contains
        "trim" -> R.string.studio_term_trim
        "extract" -> R.string.studio_term_extract
        "replace" -> R.string.studio_term_replace
        "first" -> R.string.studio_term_first
        "date" -> R.string.studio_term_date
        "uppercase" -> R.string.studio_term_uppercase
        "lowercase" -> R.string.studio_term_lowercase
        "decodeHtml" -> R.string.studio_term_decodeHtml
        "headers" -> R.string.studio_headers
        "anilistId" -> null
        "malId" -> null
        else -> null
    }
    return resource?.let { stringResource(it) } ?: key
}
