package it.lorelore.nyanime.studio

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.motion.appMotionEnabled
import eu.kanade.tachiyomi.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Graph geometry is in dp. Only visible cards compose; connection strokes invalidate separately. */
@Composable internal fun StudioCanvas(
    graph: StudioGraph,
    selected: String?,
    traces: Map<String, StudioTrace>,
    select: (String) -> Unit,
    change: (StudioGraph) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    var zoom by rememberSaveable { mutableFloatStateOf(.8f) }
    var pan by rememberSaveable(
        stateSaver = Saver<Offset, List<Float>>(save = {
            listOf(it.x, it.y)
        }, restore = { Offset(it[0], it[1]) }),
    ) { mutableStateOf(Offset(28f, 28f)) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var moving by remember { mutableStateOf<Map<String, Offset>>(emptyMap()) }
    var multi by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var connecting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    var toolsMenu by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(graph)
    val latestChange by rememberUpdatedState(change)
    val running = traces.values.any { it.running }
    val motion = appMotionEnabled()
    val background = MaterialTheme.colorScheme.surfaceContainerLowest
    val line = MaterialTheme.colorScheme.outlineVariant
    fun point(node: StudioNode): Offset = moving[node.id] ?: Offset(node.x, node.y)
    fun port(node: StudioNode, name: String, output: Boolean): Offset {
        val index = (if (output) StudioPorts.outputs(node) else StudioPorts.inputs(node)).indexOfFirst {
            it.key == name
        }.coerceAtLeast(0)
        val pos = point(node)
        return Offset(
            (pos.x + if (output) 240f else 0f) * density * zoom + pan.x,
            (pos.y + 72f + index * 32f) * density * zoom + pan.y,
        )
    }
    fun connect(target: StudioNode, input: StudioPort) {
        val from = connecting ?: return
        val source = graph.nodes.find { it.id == from.first } ?: return
        val output = StudioPorts.outputs(source).find { it.key == from.second } ?: return
        if (source.id != target.id && StudioPorts.accepts(output.type, input.type)) {
            val next = graph.copy(
                wires =
                graph.wires.filterNot { it.to == target.id && it.input == input.key } +
                    StudioWire(source.id, output.key, target.id, input.key),
            )
            if (runCatching { StudioValidation.order(next) }.isSuccess) change(next)
        }
        connecting = null
        dragEnd = null
    }
    fun fit() {
        if (graph.nodes.isEmpty() || viewport.width == 0 || viewport.height == 0) return
        val left = graph.nodes.minOf { it.x }
        val top = graph.nodes.minOf { it.y }
        val right = graph.nodes.maxOf { it.x + 240f }
        val bottom = graph.nodes.maxOf {
            it.y +
                112f +
                maxOf(StudioPorts.inputs(it).size, StudioPorts.outputs(it).size) *
                32f
        }
        val width = (right - left).coerceAtLeast(1f) * density
        val height = (bottom - top).coerceAtLeast(1f) * density
        val margin = 24f * density
        zoom = minOf((viewport.width - margin * 2) / width, (viewport.height - margin * 2) / height).coerceIn(.1f, 1.8f)
        pan =
            Offset(
                (viewport.width - width * zoom) / 2 - left * density * zoom,
                (viewport.height - height * zoom) / 2 - top * density * zoom,
            )
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            StudioIconAction(stringResource(R.string.studio_zoom_out), Icons.Outlined.ZoomOut) {
                zoom = (zoom / 1.25f).coerceAtLeast(.1f)
            }
            Text(
                "${(zoom * 100).roundToInt()}%",
                Modifier.width(44.dp),
                style = MaterialTheme.typography.labelSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            StudioIconAction(stringResource(R.string.studio_zoom_in), Icons.Outlined.ZoomIn) {
                zoom = (zoom * 1.25f).coerceAtMost(1.8f)
            }
            StudioIconAction(stringResource(R.string.studio_fit), Icons.Outlined.CenterFocusStrong, onClick = ::fit)
            StudioIconAction(
                stringResource(R.string.studio_select_many),
                Icons.Outlined.SelectAll,
                isSelected = multi,
            ) {
                multi = !multi
                selection = emptySet()
            }
            androidx.compose.foundation.layout.Box {
                StudioIconAction(stringResource(R.string.studio_advanced), Icons.Outlined.MoreVert) { toolsMenu = true }
                DropdownMenu(toolsMenu, { toolsMenu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.studio_align)) }, {
                        toolsMenu = false
                        val aligned = graph.nodes.filter { selection.isEmpty() || it.id in selection }
                            .mapIndexed { index, node -> node.copy(x = 32f + index * 280f, y = 80f) }
                            .associateBy { it.id }
                        change(graph.copy(nodes = graph.nodes.map { aligned[it.id] ?: it }))
                    })
                    DropdownMenuItem({ Text(stringResource(R.string.studio_delete_selection)) }, {
                        toolsMenu = false
                        change(
                            graph.copy(
                                nodes = graph.nodes.filterNot { it.id in selection },
                                wires = graph.wires.filterNot { it.from in selection || it.to in selection },
                            ),
                        )
                        selection = emptySet()
                    }, enabled = selection.isNotEmpty())
                }
            }
        }
        BoxWithConstraints(
            Modifier.fillMaxSize().onSizeChanged {
                val rotated = viewport.width > 0 &&
                    viewport.height > 0 &&
                    (viewport.width > viewport.height) != (it.width > it.height)
                viewport = it
                if (rotated) fit()
            }.clipToBounds().background(background).pointerInput(Unit) {
                detectTransformGestures { centroid, delta, scale, _ ->
                    val next = (zoom * scale).coerceIn(.1f, 1.8f)
                    pan = centroid - (centroid - pan) * (next / zoom) + delta
                    zoom = next
                }
            },
        ) {
            val widthPx = constraints.maxWidth.toFloat()
            val heightPx = constraints.maxHeight.toFloat()
            val positions = graph.nodes.associateBy { it.id }
            val visibleNodes by remember(graph, density, widthPx, heightPx) {
                derivedStateOf {
                    graph.nodes.filter { node ->
                        val position = point(node) * density * zoom + pan
                        val rows = maxOf(StudioPorts.inputs(node).size, StudioPorts.outputs(node).size)
                        val height = (104f + rows * 32f) * density * zoom
                        node.id in moving ||
                            (
                                position.x + 240f * density * zoom >= -80f &&
                                    position.y + height >= -80f &&
                                    position.x <= widthPx + 80f &&
                                    position.y <= heightPx + 80f
                                )
                    }
                }
            }
            val phase = if (running && motion) {
                val animation = rememberInfiniteTransition(label = "studio-execution")
                animation.animateFloat(
                    0f,
                    24f,
                    infiniteRepeatable(tween(700, easing = LinearEasing)),
                    label = "studio-wire",
                )
            } else {
                null
            }
            Canvas(Modifier.fillMaxSize()) {
                val grid = 28f * density * zoom
                var x = pan.x % grid
                while (x <
                    size.width
                ) {
                    var y =
                        pan.y % grid
                    while (y <
                        size.height
                    ) {
                        drawCircle(line.copy(alpha = .45f), 1f, Offset(x, y))
                        y += grid
                    }
                    x += grid
                }
                fun curve(start: Offset, end: Offset, color: androidx.compose.ui.graphics.Color, active: Boolean) {
                    if (start.x < -300f &&
                        end.x < -300f ||
                        start.x > size.width + 300f &&
                        end.x > size.width + 300f
                    ) {
                        return
                    }
                    val bend = (abs(end.x - start.x) * .45f).coerceAtLeast(60f * zoom)
                    val path = Path().apply {
                        moveTo(start.x, start.y)
                        cubicTo(start.x + bend, start.y, end.x - bend, end.y, end.x, end.y)
                    }
                    drawPath(
                        path,
                        color,
                        style = Stroke(
                            width = if (active) 3f * density else 1.6f * density,
                            pathEffect = if (active &&
                                motion
                            ) {
                                PathEffect.dashPathEffect(floatArrayOf(12f, 12f), phase?.value ?: 0f)
                            } else {
                                null
                            },
                        ),
                    )
                }
                graph.wires.forEach { wire ->
                    val from = positions[wire.from]
                    val to = positions[wire.to]
                    if (from != null &&
                        to != null
                    ) {
                        curve(
                            port(from, wire.output, true),
                            port(to, wire.input, false),
                            studioNodeColor(from.kind).copy(
                                alpha = if (traces[to.id]?.running ==
                                    true
                                ) {
                                    1f
                                } else {
                                    .65f
                                },
                            ),
                            traces[to.id]?.running == true,
                        )
                    }
                }
                connecting?.let { (id, output) ->
                    positions[id]?.let { from ->
                        dragEnd?.let { curve(port(from, output, true), it, studioNodeColor(from.kind), true) }
                    }
                }
            }
            visibleNodes.forEach { node ->
                val rows = maxOf(StudioPorts.inputs(node).size, StudioPorts.outputs(node).size)
                key(node.id) {
                    val color = studioNodeColor(node.kind)
                    val chosen = node.id == selected || node.id in selection
                    Surface(
                        Modifier.offset {
                            val position = point(node) * density * zoom + pan
                            IntOffset(position.x.roundToInt(), position.y.roundToInt())
                        }
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                transformOrigin = TransformOrigin(0f, 0f)
                            }.width(240.dp)
                            .border(
                                if (chosen) 2.dp else 1.dp,
                                color.copy(alpha = if (chosen) 1f else .55f),
                                RoundedCornerShape(20.dp),
                            )
                            .clickable {
                                if (multi) {
                                    selection =
                                        if (node.id in
                                            selection
                                        ) {
                                            selection - node.id
                                        } else {
                                            selection + node.id
                                        }
                                } else {
                                    select(node.id)
                                }
                            }
                            .pointerInput(node.id, node.x, node.y) {
                                var group = emptySet<String>()
                                detectDragGestures(onDragStart = {
                                    group =
                                        if (node.id in
                                            selection
                                        ) {
                                            selection
                                        } else {
                                            setOf(node.id)
                                        }
                                }, onDragCancel = { moving = emptyMap() }, onDragEnd = {
                                    val moved = moving
                                    latestChange(
                                        latest.copy(
                                            nodes = latest.nodes.map { current ->
                                                moved[current.id]?.let { current.copy(x = it.x, y = it.y) }
                                                    ?: current
                                            },
                                        ),
                                    )
                                    moving = emptyMap()
                                }) { event, delta ->
                                    event.consume()
                                    val dp = delta / (density * zoom)
                                    moving =
                                        group.associateWith { id ->
                                            val current =
                                                moving[id]
                                                    ?: latest.nodes.find { it.id == id }!!.let { Offset(it.x, it.y) }
                                            current +
                                                dp
                                        }
                                }
                            },
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = if (chosen) 6.dp else 2.dp,
                    ) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth().background(color.copy(alpha = .15f)).padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(studioNodeIcon(node.kind), null, Modifier.size(18.dp), tint = color)
                                Text(
                                    node.label.ifBlank {
                                        studioNodeName(node.kind)
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                )
                            }
                            repeat(rows) { index ->
                                Row(
                                    Modifier.fillMaxWidth().height(32.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    StudioPorts.inputs(node).getOrNull(index)?.let { input ->
                                        Text(
                                            "● ${input.key}",
                                            Modifier.clickable {
                                                connect(node, input)
                                            }.padding(horizontal = 8.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color,
                                        )
                                    }
                                        ?: Spacer(Modifier.width(1.dp))
                                    StudioPorts.outputs(node).getOrNull(index)?.let { output ->
                                        Text(
                                            "${output.key} ●",
                                            Modifier.clickable {
                                                connecting =
                                                    node.id to output.key
                                            }
                                                .pointerInput(node.id, output.key) {
                                                    detectDragGestures(onDragStart = {
                                                        connecting = node.id to output.key
                                                        dragEnd =
                                                            port(node, output.key, true)
                                                    }, onDragCancel = {
                                                        connecting = null
                                                        dragEnd =
                                                            null
                                                    }, onDragEnd = {
                                                        val end = dragEnd
                                                        val target = if (end !=
                                                            null
                                                        ) {
                                                            latest.nodes.firstNotNullOfOrNull { candidate ->
                                                                StudioPorts.inputs(candidate).firstOrNull {
                                                                    (
                                                                        port(candidate, it.key, false) -
                                                                            end
                                                                        ).getDistance() <
                                                                        38f * density
                                                                }?.let { candidate to it }
                                                            }
                                                        } else {
                                                            null
                                                        }
                                                        if (target !=
                                                            null
                                                        ) {
                                                            connect(target.first, target.second)
                                                        } else {
                                                            connecting = null
                                                            dragEnd =
                                                                null
                                                        }
                                                    }) { event, delta ->
                                                        event.consume()
                                                        dragEnd =
                                                            (dragEnd ?: port(node, output.key, true)) + delta
                                                    }
                                                }.padding(horizontal = 8.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = color,
                                        )
                                    }
                                }
                            }
                            val state = traces[node.id]
                            if (state?.running == true) {
                                LinearProgressIndicator(Modifier.fillMaxWidth(), color = color)
                            } else {
                                Text(
                                    state?.error ?: state?.let {
                                        "${it.elapsedMillis} ms"
                                    }.orEmpty(),
                                    Modifier.padding(10.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (state?.error !=
                                        null
                                    ) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
