package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.theme.LocalNyanimeStyle
import kotlinx.collections.immutable.ImmutableList
import tachiyomi.presentation.core.components.ActionButton
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction

@Composable
internal fun LibraryEmptyScreen(
    stringRes: StringResource,
    supportingText: String,
    isManga: Boolean,
    modifier: Modifier = Modifier,
    actions: ImmutableList<EmptyScreenAction>? = null,
) {
    if (!LocalNyanimeStyle.current) {
        EmptyScreen(stringRes = stringRes, modifier = modifier, actions = actions)
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Icon(
                        imageVector = if (isManga) {
                            Icons.AutoMirrored.Outlined.MenuBook
                        } else {
                            Icons.Outlined.VideoLibrary
                        },
                        contentDescription = null,
                        modifier = Modifier.padding(18.dp).size(34.dp),
                    )
                }
                Text(
                    text = stringResource(stringRes),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (!actions.isNullOrEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        actions.fastForEach {
                            ActionButton(
                                modifier = Modifier.weight(1f),
                                title = stringResource(it.stringRes),
                                icon = it.icon,
                                onClick = it.onClick,
                            )
                        }
                    }
                }
            }
        }
    }
}
