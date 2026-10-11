/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.presentation.player.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import it.lorelore.nyanime.presentation.player.VideoPlayerSheet
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Native Nyanime sheet; every existing player picker keeps its own content and commands. */
@Composable
fun PlayerSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 1.dp,
    dismissEvent: Boolean = false,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    val preferences = remember { Injekt.get<PlayerPreferences>() }
    val reduceMotion by preferences.reduceMotion().collectAsState()
    VideoPlayerSheet(
        onDismiss = onDismissRequest,
        modifier = modifier,
        dismissEvent = dismissEvent,
        title = title,
        reduceMotion = reduceMotion,
        content = content,
    )
}
