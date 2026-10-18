package com.happypipaliya.mealtracker.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Energy state synced from the phone. */
data class WatchEnergy(
    val energy: Double,
    val charging: Boolean,
    val personalised: Boolean,
    val time: Long,
    val need: String,
    val gauges: List<Triple<String, Double, String>>, // label, level, detail
) {
    companion object {
        fun load(ctx: Context): WatchEnergy? = runCatching {
            val lines = WatchPrefs(ctx).energy.lines()
            WatchEnergy(
                lines[0].toDouble(), lines[1].toBoolean(), lines[2].toBoolean(), lines[3].toLong(), lines[4],
                lines.drop(5).filter { it.isNotBlank() }.map { val p = it.split('|'); Triple(p[1], p[2].toDouble(), p.getOrElse(3) { "" }) },
            )
        }.getOrNull()
    }
}

private val ExpoOut: Easing = Easing { t -> if (t >= 1f) 1f else 1f - 2f.pow(-10f * t) }
private val EaseInOut: Easing = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

fun energyColor(level: Double): Color = if (level >= 0.5)
    lerp(Color(0xFFF5B83D), Color(0xFF2FBF71), ((level - 0.5) / 0.5).toFloat().coerceIn(0f, 1f))
else lerp(Color(0xFFE5484D), Color(0xFFF5B83D), (level / 0.5).toFloat().coerceIn(0f, 1f))

fun energyLabel(level: Double) = when {
    level >= 0.8 -> "Full of energy"
    level >= 0.6 -> "Steady"
    level >= 0.4 -> "Running lower"
    level >= 0.2 -> "Low"
    else -> "Running on empty"
}

/** Same battery as the phone: liquid fills (expo-out), ripples (linear), shifts colour, bolt pulses while charging. */
@Composable
fun WatchBattery(level: Double, modifier: Modifier, charging: Boolean = false, delayMs: Long = 0) {
    val fill = remember { Animatable(0f) }
    LaunchedEffect(level) {
        delay(delayMs)
        fill.animateTo(level.toFloat().coerceIn(0f, 1f), tween(1300, easing = ExpoOut))
    }
    val color by animateColorAsState(energyColor(fill.value.toDouble()), tween(380, easing = EaseInOut), label = "battery")
    val loop = rememberInfiniteTransition(label = "loop")
    val phase by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase")
    val bolt by loop.animateFloat(0.6f, 1f, infiniteRepeatable(tween(700, easing = EaseInOut), RepeatMode.Reverse), label = "bolt")
    val outline = Color.White.copy(alpha = 0.6f)
    Canvas(modifier) {
        val stroke = size.width * 0.05f
        val capH = size.height * 0.07f
        val body = Size(size.width - stroke, size.height - capH - stroke)
        val topLeft = Offset(stroke / 2, capH + stroke / 2)
        val r = CornerRadius(size.width * 0.17f)
        drawRoundRect(outline, Offset(size.width * 0.32f, 0f), Size(size.width * 0.36f, capH + stroke), CornerRadius(stroke))
        drawRoundRect(Color.White.copy(alpha = 0.08f), topLeft, body, r)
        val inset = stroke * 1.6f
        val inner = Path().apply {
            addRoundRect(RoundRect(topLeft.x + inset, topLeft.y + inset, topLeft.x + body.width - inset, topLeft.y + body.height - inset, CornerRadius(r.x - inset)))
        }
        clipPath(inner) {
            val innerH = body.height - 2 * inset
            val surface = topLeft.y + inset + innerH * (1f - fill.value)
            val amp = if (fill.value in 0.02f..0.98f) innerH * 0.03f else 0f
            for (layer in 0..1) {
                val wave = Path().apply {
                    moveTo(topLeft.x, size.height)
                    var x = topLeft.x
                    while (x <= topLeft.x + body.width + 2f) {
                        val k = (x - topLeft.x) / body.width
                        lineTo(x, surface + amp * sin(2 * PI * (k * 1.4 + phase + layer * 0.5)).toFloat())
                        x += 3f
                    }
                    lineTo(topLeft.x + body.width, size.height)
                    close()
                }
                drawPath(wave, color.copy(alpha = if (layer == 0) 0.45f else 1f))
            }
        }
        drawRoundRect(outline, topLeft, body, r, style = Stroke(stroke))
        if (charging) {
            val cx = size.width / 2
            val cy = topLeft.y + body.height / 2
            val s = size.width * 0.22f * bolt
            drawPath(Path().apply {
                moveTo(cx + s * 0.15f, cy - s); lineTo(cx - s * 0.55f, cy + s * 0.12f); lineTo(cx - s * 0.02f, cy + s * 0.12f)
                lineTo(cx - s * 0.15f, cy + s); lineTo(cx + s * 0.55f, cy - s * 0.12f); lineTo(cx + s * 0.02f, cy - s * 0.12f); close()
            }, Color.White.copy(alpha = 0.9f))
        }
    }
}

/** Energy on the wrist: big battery, tip, every gauge, and a quick check-in. Refreshes when the phone syncs. */
class WatchEnergyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { EnergyPage() } }
    }
}

@Composable
private fun EnergyPage() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Re-read whenever the phone sends a new state.
    DisposableEffect(Unit) {
        val prefs = ctx.getSharedPreferences("smart_alarm", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "energy") tick++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val e = remember(tick) { WatchEnergy.load(ctx) }
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(Modifier.fillMaxWidth(), state = state, autoCentering = AutoCenteringParams(itemIndex = 0)) {
            if (e == null) {
                item { Text("Open Meal Tracker on your phone once to sync your energy.", textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp)) }
                return@ScalingLazyColumn
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WatchBattery(e.energy, Modifier.size(width = 62.dp, height = 98.dp), e.charging)
                    val count = remember { Animatable(0f) }
                    LaunchedEffect(e.energy) { count.animateTo((e.energy * 100).toFloat(), tween(1300, easing = ExpoOut)) }
                    Text("${count.value.roundToInt()}%", style = MaterialTheme.typography.display2, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    Text(energyLabel(e.energy) + if (e.charging) " · charging" else "", style = MaterialTheme.typography.caption1, color = energyColor(e.energy))
                }
            }
            item { Text(e.need, style = MaterialTheme.typography.body2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp)) }
            items(e.gauges.size) { i ->
                val (label, level, detail) = e.gauges[i]
                val enter = remember { Animatable(0f) }
                LaunchedEffect(Unit) { delay(120L + i * 70L); enter.animateTo(1f, tween(600, easing = ExpoOut)) }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
                        .graphicsLayer { alpha = enter.value; translationX = (1f - enter.value) * 40f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WatchBattery(level, Modifier.size(width = 20.dp, height = 32.dp), delayMs = 150L + i * 70L)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(label, style = MaterialTheme.typography.caption1, modifier = Modifier.weight(1f))
                            Text("${(level * 100).roundToInt()}%", style = MaterialTheme.typography.caption1, color = energyColor(level))
                        }
                        Text(detail, style = MaterialTheme.typography.caption3, maxLines = 2)
                    }
                }
            }
            val factors = WatchPrefs(ctx).energyFactors.lines().mapNotNull { l ->
                val p = l.split('|'); p.getOrNull(1)?.toDoubleOrNull()?.let { p[0] to it }
            }
            if (factors.isNotEmpty()) item { Text("Affecting your energy", style = MaterialTheme.typography.caption1, modifier = Modifier.padding(top = 6.dp)) }
            items(factors.size) { i ->
                val (label, pts) = factors[i]
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    Text(label, style = MaterialTheme.typography.caption2, modifier = Modifier.weight(1f), maxLines = 1)
                    Text((if (pts >= 0) "+" else "−") + "${kotlin.math.abs(pts).roundToInt()}%", style = MaterialTheme.typography.caption2,
                        color = if (pts >= 0) Color(0xFF2FBF71) else Color(0xFFE5484D))
                }
            }
            item {
                Chip(
                    onClick = { ctx.startActivity(Intent(ctx, ActivityLogActivity::class.java)) },
                    label = { Text("Log activity") },
                    secondaryLabel = { Text("Study, massage chair, yoga") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    onClick = { ctx.startActivity(Intent(ctx, EnergyActivity::class.java)) },
                    label = { Text("How do you feel?") },
                    secondaryLabel = { Text("Rate energy with the bezel") },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
            item {
                val mins = ((System.currentTimeMillis() - e.time) / 60_000).toInt()
                Text(
                    (if (e.personalised) "Personalised · " else "") + if (mins < 1) "Updated just now" else "Updated $mins min ago",
                    style = MaterialTheme.typography.caption3,
                )
            }
        }
    }
}
