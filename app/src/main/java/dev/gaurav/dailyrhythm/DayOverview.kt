package dev.gaurav.dailyrhythm

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun DayOverview(rows: List<PlannedOccurrence>, onSamples: () -> Unit) {
    val done = rows.count { it.state == HistoryState.DONE || it.state == HistoryState.LATE }
    val open = rows.count { it.state == HistoryState.UNLOGGED }
    val skipped = rows.count { it.state == HistoryState.SKIPPED }
    val progress by animateFloatAsState(
        if (rows.isEmpty()) 0f else done.toFloat() / rows.size, tween(320), label = "Day completion")
    val compact = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), shape = RoundedCornerShape(24.dp),
        color = colors.inverseSurface, contentColor = colors.inverseOnSurface) {
        Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(colors.inverseSurface,
            lerp(colors.inverseSurface, colors.tertiary, .34f)))).padding(horizontal = 20.dp, vertical = if (compact) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val title = when {
                    rows.isEmpty() -> "Nothing scheduled"
                    open == 0 && skipped == 0 -> "All done"
                    open == 0 -> "Day recorded"
                    open == 1 -> "1 reminder left"
                    else -> "$open reminders left"
                }
                Text(title, Modifier.semantics { heading() }, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium)
                if (rows.isEmpty()) {
                    Text("Add a reminder for this day.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onSamples, contentPadding = PaddingValues(horizontal = 0.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary)) { Text("Sample routines") }
                } else {
                    Text(buildString {
                        append("$done of ${rows.size} completed")
                        if (skipped > 0) append(" · $skipped skipped")
                    }, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // A rising sun from the launcher mark. Its outer arc fills as the day is completed.
            if (!compact) Canvas(Modifier.size(74.dp, 52.dp)) {
                val radius = size.width * .39f
                val horizon = Offset(size.width / 2f, size.height * .76f)
                val origin = Offset(horizon.x - radius, horizon.y - radius)
                val arc = Size(radius * 2, radius * 2)
                val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                drawArc(colors.inverseOnSurface.copy(alpha = .32f), 180f, 180f, false, origin, arc, style = stroke)
                if (progress > 0f) drawArc(colors.inversePrimary, 180f, 180f * progress, false, origin, arc, style = stroke)
                for (index in 0..8) {
                    val angle = Math.PI * (1 + index / 8.0)
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    drawLine(colors.inversePrimary, horizon + direction * (radius + 4.dp.toPx()),
                        horizon + direction * (radius + 6.dp.toPx()), 1.dp.toPx())
                }
                val sunRadius = radius * .55f
                drawArc(colors.inversePrimary, 180f, 180f, true, Offset(horizon.x - sunRadius, horizon.y - sunRadius),
                    Size(sunRadius * 2, sunRadius * 2))
                drawLine(colors.inverseOnSurface, Offset(0f, horizon.y), Offset(size.width, horizon.y), 2.dp.toPx(), StrokeCap.Round)
                drawLine(colors.inverseOnSurface.copy(alpha = .55f), Offset(size.width * .22f, horizon.y + 6.dp.toPx()),
                    Offset(size.width * .78f, horizon.y + 6.dp.toPx()), 1.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}
