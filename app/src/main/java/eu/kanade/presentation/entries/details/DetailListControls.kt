package eu.kanade.presentation.entries.details

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.motion.ModernMotion
import eu.kanade.presentation.motion.modernMotionEnabled
import eu.kanade.tachiyomi.R

internal val DetailRowMinHeight = 72.dp

@Composable
internal fun DetailListControls(
    plural: String,
    count: Int,
    total: Int,
    hasFilters: Boolean,
    query: String,
    searching: Boolean,
    requestFocus: Boolean,
    onFocusRequested: () -> Unit,
    compact: Boolean,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onFilter: () -> Unit,
    onIndex: () -> Unit,
    canResume: Boolean,
    onResume: () -> Unit,
    enabled: Boolean = true,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val motion = modernMotionEnabled()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(plural, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(count.toString(), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (compact) {
                DetailIcon(
                    TitleDetailsIcons.Resume,
                    stringResource(R.string.detail_go_resume),
                    onResume,
                    canResume && enabled,
                )
            }
            DetailIcon(TitleDetailsIcons.Search, stringResource(R.string.detail_search_items), onSearch, enabled)
            DetailIcon(TitleDetailsIcons.Index, stringResource(R.string.detail_index), onIndex, count > 0 && enabled)
            DetailIcon(TitleDetailsIcons.Filter, stringResource(R.string.detail_filters), onFilter, enabled)
        }
        if (!compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (hasFilters) {
                    TextButton(onClick = onFilter, enabled = enabled, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.detail_filtered, count, total), fontSize = 11.sp)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onResume, enabled = canResume && enabled) {
                    Icon(TitleDetailsIcons.Resume, null, Modifier.size(16.dp))
                    Text(stringResource(R.string.detail_go_resume), Modifier.padding(start = 6.dp), fontSize = 12.sp)
                }
            }
        }
        AnimatedVisibility(
            visible = searching,
            enter = if (motion) {
                ModernMotion.enter() + expandVertically(tween(ModernMotion.RESIZE_MILLIS), expandFrom = Alignment.Top)
            } else {
                androidx.compose.animation.EnterTransition.None
            },
            exit = if (motion) {
                ModernMotion.exit() + shrinkVertically(tween(ModernMotion.RESIZE_MILLIS), shrinkTowards = Alignment.Top)
            } else {
                androidx.compose.animation.ExitTransition.None
            },
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).focusRequester(focus),
                decorationBox = { field ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 46.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                            .padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(TitleDetailsIcons.Search, null, Modifier.size(19.dp))
                        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            if (query.isEmpty()) {
                                Text(
                                    stringResource(R.string.detail_number_search),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            field()
                        }
                        DetailIcon(TitleDetailsIcons.Close, stringResource(R.string.detail_close_search), onSearch)
                    }
                },
            )
            LaunchedEffect(requestFocus) {
                if (requestFocus) {
                    focus.requestFocus()
                    onFocusRequested()
                }
            }
        }
    }
}

@Composable
internal fun DetailListRowFrame(highlighted: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val opacity by animateFloatAsState(
        if (highlighted) 0.6f else 0f,
        tween(if (modernMotionEnabled()) ModernMotion.RESIZE_MILLIS else 0),
        label = "detail_jump_highlight",
    )
    Box(modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = opacity), RoundedCornerShape(10.dp))) {
        content()
    }
}
