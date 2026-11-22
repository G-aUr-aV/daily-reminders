package dev.gaurav.dailyrhythm

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

internal class ReminderOrder(private val storage: KeyValue) {
    private fun boards() = runCatching { JSONObject(storage.get("reminder.order.v1").orEmpty()) }.getOrDefault(JSONObject())
    fun read(board: String): List<String> {
        val ids = boards().optJSONArray(board) ?: return emptyList()
        return List(ids.length()) { ids.getString(it) }
    }
    fun save(board: String, ids: List<String>) {
        val boards = boards()
        boards.put(board, JSONArray(ids))
        val dates = boards.keys().asSequence().filter { it.startsWith("today/") }.sorted().toList()
        dates.dropLast(120).forEach(boards::remove)
        storage.put("reminder.order.v1", boards.toString())
    }
}

internal fun orderTiles(visible: List<String>, saved: List<String>): List<String> {
    val included = visible.toHashSet()
    val ordered = saved.filter { it in included }.distinct()
    val orderedSet = ordered.toHashSet()
    return ordered + visible.filterNot { it in orderedSet }
}

internal data class TileAction(val label: String, val destructive: Boolean = false, val run: () -> Unit)
internal data class ReminderTile(
    val id: String, val title: String, val time: String?, val caption: String?,
    val primary: TileAction?, val secondary: TileAction?, val onOpen: () -> Unit, val category: ReminderCategory? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderBoard(tiles: List<ReminderTile>, board: String, store: ReminderOrder,
                           scroll: ScrollState, viewport: Rect, allowReorder: Boolean = true, onError: () -> Unit) {
    var saved by remember(board) { mutableStateOf(store.read(board)) }
    var preview by remember(board) { mutableStateOf<List<String>?>(null) }
    var dragging by remember(board) { mutableStateOf<String?>(null) }
    var revealed by remember(board) { mutableStateOf<Pair<String, Boolean>?>(null) }
    var top by remember(board) { mutableFloatStateOf(0f) }
    val bounds = remember(board) { mutableStateMapOf<String, Rect>() }
    val byId = tiles.associateBy(ReminderTile::id)
    val ids = orderTiles(tiles.map(ReminderTile::id), preview ?: saved)
    val visible by rememberUpdatedState(ids)
    val viewportNow by rememberUpdatedState(viewport)
    val haptic = LocalHapticFeedback.current

    fun save(order: List<String>) {
        runCatching { store.save(board, order) }.onSuccess { saved = order }.onFailure { onError() }
    }
    fun move(id: String, index: Int): List<String> =
        visible.toMutableList().apply { remove(id); add(index.coerceIn(0, size), id) }
    fun updateDrag() {
        val id = dragging ?: return
        val index = visible.indexOf(id)
        val card = bounds[id] ?: return
        val center = top + card.height / 2f
        val next = visible.count { it != id && (bounds[it]?.center?.y ?: Float.POSITIVE_INFINITY) < center }
        if (index >= 0 && next != index) preview = move(id, next)
    }

    LaunchedEffect(dragging, board) {
        while (dragging != null) {
            val id = dragging ?: break
            val card = bounds[id]
            val edge = 68f
            if (card != null) {
                val fromStart = top + card.height / 2f - viewportNow.top
                val fromEnd = viewportNow.bottom - (top + card.height / 2f)
                val amount = when {
                    fromStart < edge -> -(edge - fromStart).coerceIn(0f, 24f)
                    fromEnd < edge -> (edge - fromEnd).coerceIn(0f, 24f)
                    else -> 0f
                }
                if (amount != 0f && scroll.scrollBy(amount) != 0f) updateDrag()
            }
            delay(16)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ids.forEach { id -> key(id) {
            val tile = byId.getValue(id)
            val first = tile.primary
            val second = tile.secondary
            val index = ids.indexOf(id)
            val shape = RoundedCornerShape(16.dp)
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            val direction = if (rtl) -1f else 1f
            val density = LocalDensity.current
            val travel = with(density) { 112.dp.toPx() }
            val threshold = with(density) { 28.dp.toPx() }
            val side = revealed?.takeIf { it.first == id }?.second
            val settled = when (side) { true -> travel; false -> -travel; null -> 0f }
            var tracking by remember { mutableStateOf<Float?>(null) }
            val animated by animateFloatAsState(settled, label = "Swipe actions")
            val offset = tracking ?: animated
            val action = if (offset > 0) first else second
            val gesture = rememberDraggableState { delta ->
                val current = tracking ?: settled
                tracking = (current + delta * direction).coerceIn(
                    if (second == null) 0f else -travel, if (first == null) 0f else travel
                )
            }
            Box(Modifier.fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    val point = coordinates.positionInRoot()
                    bounds[id] = Rect(point.x, point.y, point.x + coordinates.size.width, point.y + coordinates.size.height)
                }
                .zIndex(if (dragging == id) 1f else 0f)
                .graphicsLayer {
                    translationY = if (dragging == id) top - (bounds[id]?.top ?: top) else 0f
                }
                .clip(shape)
                .draggable(gesture, Orientation.Horizontal, enabled = dragging == null && (first != null || second != null),
                    onDragStarted = {
                        if (revealed?.first != id) revealed = null
                        tracking = settled
                    },
                    onDragStopped = {
                        val distance = tracking ?: 0f
                        revealed = when {
                            distance >= threshold && first != null -> id to true
                            distance <= -threshold && second != null -> id to false
                            else -> null
                        }
                        tracking = null
                    })) {
                if (action != null && offset != 0f) {
                    val background = if (action.destructive) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.primaryContainer
                    Surface(Modifier.matchParentSize(), color = background) {
                        Box(contentAlignment = if (offset > 0) Alignment.CenterStart else Alignment.CenterEnd) {
                            if (side != null && tracking == null) TextButton(
                                onClick = { revealed = null; action.run() },
                                modifier = Modifier.width(112.dp).fillMaxHeight(),
                                colors = ButtonDefaults.textButtonColors(contentColor = if (action.destructive)
                                    MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer)
                            ) { Text(action.label) }
                        }
                    }
                }
                Surface(modifier = Modifier.absoluteOffset { IntOffset((offset * direction).roundToInt(), 0) },
                    shape = shape, color = if (dragging == id) MaterialTheme.colorScheme.surfaceContainerHighest
                    else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)
                        .pointerInput(id, board, allowReorder) {
                            if (!allowReorder) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    val origin = bounds[id]
                                    if (origin != null) {
                                        revealed = null
                                        top = origin.top
                                        preview = visible
                                        dragging = id
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                },
                                onDrag = { change, delta ->
                                    if (dragging == id) {
                                        change.consume()
                                        top += delta.y
                                        updateDrag()
                                    }
                                },
                                onDragEnd = {
                                    if (dragging == id) preview?.let(::save)
                                    dragging = null
                                    preview = null
                                },
                                onDragCancel = { dragging = null; preview = null }
                            )
                        }
                        .clickable { if (revealed?.first == id) revealed = null else { revealed = null; tile.onOpen() } }
                        .semantics {
                            customActions = buildList {
                                if (allowReorder && index > 0) add(CustomAccessibilityAction("Move up") { save(move(id, index - 1)); true })
                                if (allowReorder && index < ids.lastIndex) add(CustomAccessibilityAction("Move down") { save(move(id, index + 1)); true })
                                first?.let { add(CustomAccessibilityAction(it.label) { it.run(); true }) }
                                second?.let { add(CustomAccessibilityAction(it.label) { it.run(); true }) }
                            }
                        }
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 13.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(tile.title, style = MaterialTheme.typography.titleMedium)
                            tile.category?.let { CategoryLabel(it) }
                            tile.caption?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        tile.time?.let {
                            Text(it, Modifier.widthIn(max = 84.dp), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (allowReorder && ids.size > 1) ReminderIcon(ReminderGlyph.DRAG) else ReminderIcon(ReminderGlyph.NEXT)
                    }
                }
            }
        } }
    }
}
