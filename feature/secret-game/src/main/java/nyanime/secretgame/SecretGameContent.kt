package nyanime.secretgame

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

/** The only UI entry point imported by the host application. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecretGameContent(onBack: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var credits by remember { mutableStateOf(false) }
    var clear by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var leaveUnsaved by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    key(attempt) {
        val engine = remember { SandspielEngine(context) }
        val state by engine.state.collectAsState()
        val leave: () -> Unit = {
            if (!leaving) {
                leaving = true
                scope.launch {
                    val saved = engine.checkpoint()
                    if (saved) {
                        onBack()
                    } else {
                        leaving = false
                        leaveUnsaved = true
                    }
                }
            }
        }
        BackHandler(onBack = leave)
        DisposableEffect(engine, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) engine.foreground(false)
                if (event == Lifecycle.Event.ON_RESUME) engine.foreground(true)
            }
            lifecycle.lifecycle.addObserver(observer)
            engine.foreground(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            onDispose {
                lifecycle.lifecycle.removeObserver(observer)
                engine.close()
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.secret_game_title)) },
                    navigationIcon = {
                        IconButton(onClick = leave) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.secret_game_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            credits = true
                        }) { Icon(Icons.Outlined.Info, stringResource(R.string.secret_game_credits)) }
                    },
                )
            },
        ) { insets ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
                val board = remember(engine) {
                    movableContentOf<Modifier, GameUiState> { modifier, boardState ->
                        Box(modifier, contentAlignment = Alignment.Center) {
                            val description = stringResource(R.string.secret_game_board)
                            AndroidView(
                                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
                                factory = { ctx ->
                                    FrameLayout(ctx).also { container ->
                                        runCatching {
                                            val view = WebView(ctx)
                                            container.addView(view, FrameLayout.LayoutParams(-1, -1))
                                            engine.attach(view)
                                        }.onFailure { engine.unavailable(it) }
                                    }
                                },
                            )
                            if (boardState.loading) {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    Column(
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        CircularProgressIndicator()
                                        Text(
                                            stringResource(R.string.secret_game_loading),
                                            Modifier.padding(top = 16.dp),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                            }
                            if (boardState.failure in setOf(GameFailure.GRAPHICS, GameFailure.RESTORE)) {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    Column(
                                        Modifier.padding(20.dp),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            stringResource(
                                                if (boardState.failure ==
                                                    GameFailure.RESTORE
                                                ) {
                                                    R.string.secret_game_restore_error
                                                } else {
                                                    R.string.secret_game_graphics_error
                                                },
                                            ),
                                        )
                                        TextButton(onClick = {
                                            attempt++
                                        }) { Text(stringResource(R.string.secret_game_retry)) }
                                        if (boardState.failure ==
                                            GameFailure.RESTORE
                                        ) {
                                            TextButton(onClick = {
                                                clear = true
                                            }) { Text(stringResource(R.string.secret_game_clear)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                val controls: @Composable (Modifier) -> Unit = { modifier ->
                    GameControls(state, modifier, { engine.command(it.first, it.second) }, { clear = true })
                }
                if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        board(Modifier.weight(1f).fillMaxSize(), state)
                        controls(Modifier.width(300.dp).align(Alignment.CenterVertically))
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        board(Modifier.weight(1f).fillMaxWidth(), state)
                        controls(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    }
                }
            }
        }
        if (clear) {
            AlertDialog(
                onDismissRequest = { clear = false },
                title = { Text(stringResource(R.string.secret_game_clear_title)) },
                text = { Text(stringResource(R.string.secret_game_clear_description)) },
                confirmButton = {
                    TextButton(onClick = {
                        clear = false
                        if (state.failure ==
                            GameFailure.RESTORE
                        ) {
                            engine.startFresh()
                        } else {
                            engine.command("clear")
                        }
                    }) { Text(stringResource(R.string.secret_game_clear)) }
                },
                dismissButton = {
                    TextButton(onClick = { clear = false }) { Text(stringResource(R.string.secret_game_cancel)) }
                },
            )
        }
    }
    if (credits) {
        AlertDialog(
            onDismissRequest = { credits = false },
            title = { Text(stringResource(R.string.secret_game_credits)) },
            text = { Text(stringResource(R.string.secret_game_credits_body)) },
            confirmButton = {
                TextButton(onClick = { credits = false }) { Text(stringResource(R.string.secret_game_done)) }
            },
        )
    }
    if (leaveUnsaved) {
        AlertDialog(
            onDismissRequest = { leaveUnsaved = false },
            title = { Text(stringResource(R.string.secret_game_leave_title)) },
            text = { Text(stringResource(R.string.secret_game_save_error)) },
            confirmButton = {
                TextButton(onClick = {
                    leaveUnsaved = false
                    onBack()
                }) { Text(stringResource(R.string.secret_game_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { leaveUnsaved = false }) { Text(stringResource(R.string.secret_game_stay)) }
            },
        )
    }
}

@Composable
private fun GameControls(
    state: GameUiState,
    modifier: Modifier,
    command: (Pair<String, Int?>) -> Unit,
    clear: () -> Unit,
) {
    val names = stringArrayResource(R.array.secret_game_elements)
    val ids = remember { listOf(2, 3, 1, 13, 9, 4, 6, 8, 7, 11, 19, 18, 12, 14, 16, 15, 17, 5, 0) }
    val colors =
        remember {
            listOf(
                0xFFDFC27B,
                0xFF70ABF4,
                0xFFB6B4C0,
                0xFF9991AB,
                0xFFB9E8F6,
                0xFFB9BA91,
                0xFFFFAD61,
                0xFFFF745F,
                0xFFB98970,
                0xFF93CD80,
                0xFFD0DC8D,
                0xFFC6A8DD,
                0xFFB5EB74,
                0xFFD6B59F,
                0xFFB5A7B1,
                0xFFE49BA0,
                0xFFEEAAA7,
                0xFF8DD5D0,
                0xFFCBC3D0,
            )
        }
    val enabled = !state.loading && state.failure !in setOf(GameFailure.GRAPHICS, GameFailure.RESTORE)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.failure ==
            GameFailure.SAVE
        ) {
            Text(
                stringResource(R.string.secret_game_save_error),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (state.paused) R.string.secret_game_paused else R.string.secret_game_drawing),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(enabled = enabled, onClick = { command((if (state.paused) "resume" else "pause") to null) }) {
                Icon(
                    if (state.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    stringResource(if (state.paused) R.string.secret_game_play else R.string.secret_game_pause),
                )
            }
            IconButton(enabled = enabled, onClick = {
                command("undo" to null)
            }) { Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.secret_game_undo)) }
            IconButton(enabled = enabled, onClick = clear) {
                Icon(Icons.Outlined.RestartAlt, stringResource(R.string.secret_game_clear))
            }
        }
        Text(stringResource(R.string.secret_game_brush), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(5) { index ->
                val label = stringResource(R.string.secret_game_brush_size, index + 1)
                val dot = Modifier.size((4 + index * 3).dp).background(MaterialTheme.colorScheme.onSurface, CircleShape)
                FilterChip(
                    selected = state.brush == index,
                    enabled = enabled,
                    onClick = { command("brush" to index) },
                    label = {
                        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                            Box(dot)
                        }
                    },
                    modifier = Modifier.weight(1f).semantics { contentDescription = label },
                )
            }
        }
        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().height(112.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                FilterChip(
                    selected = state.material == id,
                    enabled = enabled,
                    onClick = { command("material" to id) },
                    label = { Text(names[index]) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(Color(colors[index]), CircleShape)) },
                )
            }
        }
    }
}
