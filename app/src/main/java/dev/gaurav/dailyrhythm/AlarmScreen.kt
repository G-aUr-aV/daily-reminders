package dev.gaurav.dailyrhythm

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.Instant
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlarmScreen(display: AlarmDisplay, onAction: (String) -> Unit, onSnooze: (Instant) -> Unit) {
    var showSnoozes by remember(display.token) { mutableStateOf(false) }
    AgendaTheme(dark = true) {
        val colors = MaterialTheme.colorScheme
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Brush.radialGradient(
                listOf(colors.primaryContainer, colors.background), radius = with(LocalDensity.current) { 410.dp.toPx() }
            )).windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.safeGestures.only(WindowInsetsSides.Bottom))),
                contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(display.time, color = colors.primary, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"))
                    Text(display.title, color = colors.onBackground, textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium)
                    display.category?.let { CategoryLabel(it) }
                    if (display.queued != 0) Text(
                        if (display.queued == 1) "1 more alarm" else "${display.queued} more alarms",
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    display.steps.forEach { Text(it, color = colors.onSurfaceVariant, textAlign = TextAlign.Center) }
                    Spacer(Modifier.height(8.dp))
                    if (display.canComplete) Button(onClick = { onAction("done") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        ReminderIcon(ReminderGlyph.COMPLETE)
                        Spacer(Modifier.width(8.dp))
                        Text("Done")
                    }
                    SlideToStop { onAction("stop") }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilledTonalButton(onClick = { onAction("snooze5") }) { Text("Snooze 5 min") }
                        FilledTonalButton(onClick = { onAction("snooze10") }) { Text("Snooze 10 min") }
                    }
                    TextButton(onClick = { showSnoozes = !showSnoozes }) {
                        Text(if (showSnoozes) "Hide snooze options" else "More snooze options")
                    }
                    if (showSnoozes) Column(Modifier.fillMaxWidth()) { SnoozeOptions(onSnooze) }
                }
            }
        }
    }
}

@Composable
private fun SlideToStop(onStop: () -> Unit) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(5.dp)) {
            val thumb = 52.dp
            val maxOffset = with(density) { (maxWidth - thumb).coerceAtLeast(0.dp).toPx() }
            var offset by remember { mutableFloatStateOf(0f) }
            val dragState = rememberDraggableState { delta -> offset = (offset + delta).coerceIn(0f, maxOffset) }
            Box(Modifier.fillMaxWidth().heightIn(min = 58.dp)
                .draggable(dragState, Orientation.Horizontal, reverseDirection = rtl, onDragStopped = {
                    val confirmed = maxOffset > 0 && offset >= maxOffset * .84f
                    offset = 0f
                    if (confirmed) onStop()
                })
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.Spacebar)) {
                        onStop()
                        true
                    } else false
                }
                .focusable()
                .semantics {
                    role = Role.Button
                    contentDescription = "Slide to stop"
                    onClick("Stop alarm") { onStop(); true }
                }) {
                Text("Slide to stop", Modifier.align(Alignment.CenterStart).padding(start = thumb + 12.dp, end = 8.dp),
                    style = MaterialTheme.typography.titleMedium)
                Surface(Modifier.align(Alignment.CenterStart).offset { IntOffset(offset.roundToInt(), 0) }.size(thumb),
                    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary) {
                    Box(contentAlignment = Alignment.Center) { ReminderIcon(ReminderGlyph.NEXT) }
                }
            }
        }
    }
}
