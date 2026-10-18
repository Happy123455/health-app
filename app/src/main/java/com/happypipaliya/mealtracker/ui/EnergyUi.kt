package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.happypipaliya.mealtracker.Meal
import java.io.File
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.activity.compose.rememberLauncherForActivityResult
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.EnergyEngine
import com.happypipaliya.mealtracker.HealthSync
import com.happypipaliya.mealtracker.UiRefresh
import com.happypipaliya.sleepcore.Calibration
import com.happypipaliya.sleepcore.EnergyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Last computed state, so screens show something at once instead of an empty battery. */
private object EnergyCache { var state: EnergyState? = null }

/** Energy state, recomputed after any data change, on resume, and every 5 minutes (fuel drains with time). */
@Composable
fun rememberEnergy(): EnergyState? {
    val ctx = LocalContext.current
    val change by Db.changes.collectAsState()
    val resume by UiRefresh.ticks.collectAsState()
    var state by remember { mutableStateOf(EnergyCache.state) }
    LaunchedEffect(change, resume) {
        while (true) {
            runCatching { EnergyEngine.compute(ctx) }.getOrNull()?.let { state = it; EnergyCache.state = it }
            delay(5 * 60_000L)
        }
    }
    return state
}

fun energyColor(level: Double): Color = when {
    level >= 0.5 -> lerp(Color(0xFFF5B83D), Color(0xFF2FBF71), ((level - 0.5) / 0.5).toFloat().coerceIn(0f, 1f))
    else -> lerp(Color(0xFFE5484D), Color(0xFFF5B83D), (level / 0.5).toFloat().coerceIn(0f, 1f))
}

fun energyLabel(level: Double) = when {
    level >= 0.8 -> "Full of energy"
    level >= 0.6 -> "Steady"
    level >= 0.4 -> "Running lower"
    level >= 0.2 -> "Low"
    else -> "Running on empty"
}

/** True when quick fuel has been rising over the last ~30 minutes (a meal is being absorbed). */
fun EnergyState.charging(): Boolean {
    val t = timeline
    return t.size > 3 && t.last().liver > t[t.size - 4].liver + 0.01
}

/**
 * A battery drawn by hand: the liquid fills to [level] (expo-out), its surface ripples gently
 * (linear, endless), its colour slides red → amber → green, and a bolt pulses while charging.
 */
@Composable
fun Battery(level: Double, modifier: Modifier, charging: Boolean = false, memoryKey: String? = null) {
    val fill = remember { Animatable(memoryKey?.let { MotionMemory.values[it] } ?: 0f) }
    LaunchedEffect(level) {
        memoryKey?.let { MotionMemory.values[it] = level.toFloat() }
        fill.animateTo(level.toFloat().coerceIn(0f, 1f), tween(Motion.HERO + 300, easing = Motion.ExpoOut))
    }
    val color by animateColorAsState(energyColor(fill.value.toDouble()), tween(Motion.BASE, easing = Motion.EaseInOut), label = "battery")
    val waves = rememberInfiniteTransition(label = "waves")
    val phase by waves.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = Motion.Linear)), label = "phase")
    val bolt by waves.animateFloat(0.6f, 1f, infiniteRepeatable(tween(700, easing = Motion.EaseInOut), RepeatMode.Reverse), label = "bolt")
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    Canvas(modifier) {
        val stroke = size.width * 0.045f
        val capH = size.height * 0.07f
        val body = Size(size.width - stroke, size.height - capH - stroke)
        val topLeft = Offset(stroke / 2, capH + stroke / 2)
        val r = CornerRadius(size.width * 0.16f)
        drawRoundRect(outline, Offset(size.width * 0.32f, 0f), Size(size.width * 0.36f, capH + stroke), CornerRadius(stroke))
        drawRoundRect(track, topLeft, body, r)
        val inset = stroke * 1.6f
        val inner = Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(topLeft.x + inset, topLeft.y + inset, topLeft.x + body.width - inset, topLeft.y + body.height - inset, CornerRadius(r.x - inset)))
        }
        clipPath(inner) {
            val innerTop = topLeft.y + inset
            val innerH = body.height - 2 * inset
            val surface = innerTop + innerH * (1f - fill.value)
            val amp = if (fill.value in 0.02f..0.98f) innerH * 0.025f else 0f
            for (layer in 0..1) {
                val wave = Path()
                val left = topLeft.x
                val right = topLeft.x + body.width
                wave.moveTo(left, size.height)
                var x = left
                while (x <= right + 2f) {
                    val k = (x - left) / body.width
                    val y = surface + amp * sin(2 * PI * (k * 1.4 + phase + layer * 0.5)).toFloat()
                    wave.lineTo(x, y)
                    x += 4f
                }
                wave.lineTo(right, size.height)
                wave.close()
                drawPath(wave, color.copy(alpha = if (layer == 0) 0.45f else 1f))
            }
        }
        drawRoundRect(outline, topLeft, body, r, style = Stroke(stroke))
        if (charging) {
            val cx = size.width / 2
            val cy = topLeft.y + body.height / 2
            val s = size.width * 0.22f * bolt
            val p = Path().apply {
                moveTo(cx + s * 0.15f, cy - s)
                lineTo(cx - s * 0.55f, cy + s * 0.12f)
                lineTo(cx - s * 0.02f, cy + s * 0.12f)
                lineTo(cx - s * 0.15f, cy + s)
                lineTo(cx + s * 0.55f, cy - s * 0.12f)
                lineTo(cx + s * 0.02f, cy - s * 0.12f)
                close()
            }
            drawPath(p, Color.White.copy(alpha = 0.9f))
        }
    }
}

/** Today: the energy battery, what to eat next, and small fuel gauges. Tap for details. */
@Composable
fun EnergyCard(modifier: Modifier, onOpen: () -> Unit) {
    val s = rememberEnergy() ?: return
    val interaction = remember { MutableInteractionSource() }
    val pct = animateCount(s.energy * 100, memoryKey = "energy-pct")
    Card(
        onClick = onOpen,
        interactionSource = interaction,
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Battery(s.energy, Modifier.size(width = 64.dp, height = 104.dp), s.charging(), memoryKey = "energy-battery")
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("Energy", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${pct.roundToInt()}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    AnimatedContent(
                        targetState = energyLabel(s.energy) + if (s.charging()) " · charging" else "",
                        transitionSpec = { fadeIn(tween(Motion.BASE, easing = Motion.EaseInOut)).togetherWith(fadeOut(tween(Motion.QUICK))) },
                        label = "label",
                    ) { Text(it, style = MaterialTheme.typography.titleSmall, color = energyColor(s.energy)) }
                }
            }
            Text(s.need, style = MaterialTheme.typography.bodyMedium)
            if (s.missingMeals) QuickAddRow()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                s.gauges.forEachIndexed { i, g -> MiniGauge(g.label, g.level, i) }
            }
        }
    }
}

@Composable
private fun MiniGauge(label: String, level: Double, index: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(60.dp).enter(index + 1, dy = 12.dp)) {
        Battery(level, Modifier.size(width = 22.dp, height = 36.dp), memoryKey = "mini-$label")
        Text("${(level * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        Text(label.substringBefore(" "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** "Ate without a photo?" Snack / Meal / Big meal, estimated from your own average meal. */
@Composable
fun QuickAddRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var added by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(added) { if (added != null) { delay(2500); added = null } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimatedContent(
            targetState = added?.let { "✓ Added a $it" } ?: "Ate without a photo? Add it:",
            transitionSpec = { (scaleIn(tween(Motion.BASE, easing = Motion.BackOut), initialScale = 0.8f) + fadeIn()).togetherWith(fadeOut(tween(Motion.QUICK))) },
            label = "quick",
        ) { Text(it, style = MaterialTheme.typography.labelLarge) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnergyEngine.QuickSize.entries.forEach { size ->
                val press = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = { scope.launch { EnergyEngine.quickAdd(ctx, size); added = size.label.lowercase() } },
                    interactionSource = press,
                    modifier = Modifier.pressScale(press, 0.92f),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text(size.label) }
            }
        }
    }
}

/** Full energy page: battery, check-in, every gauge, a 24-hour fuel chart and how it works. */
@Composable
fun EnergyScreen(onBack: () -> Unit, onMuscles: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = rememberEnergy()
    val checkIns by rememberLoad("check-ins") { Db.get(ctx).checkIns() }
    val recentMeals by rememberLoad("fuel-meals") {
        val today = java.time.LocalDate.now()
        Db.get(ctx).doneMealsBetween(today.minusDays(1).toString(), today.toString())
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header("Energy", "From your meals, workouts, sleep and recovery", onBack) }
        if (s == null) return@LazyColumn
        item {
            Column(Modifier.fillMaxWidth().enter(1), horizontalAlignment = Alignment.CenterHorizontally) {
                Battery(s.energy, Modifier.size(width = 120.dp, height = 190.dp), s.charging(), memoryKey = "energy-battery-big")
                Text("${animateCount(s.energy * 100, memoryKey = "energy-pct-big").roundToInt()}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                Text(energyLabel(s.energy), style = MaterialTheme.typography.titleMedium, color = energyColor(s.energy))
                Text(
                    if (s.personalised) "Personalised from your ${s.checkIns} check-ins"
                    else "Standard model · rate your energy ${Calibration.MIN_CHECK_INS - s.checkIns.coerceAtMost(Calibration.MIN_CHECK_INS)} more times to personalise it",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item { TipCard("What you need now", s.need, Modifier.enter(2)) }
        item {
            Card(Modifier.fillMaxWidth().enter(2)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${s.mealsLoggedToday} meal${if (s.mealsLoggedToday == 1) "" else "s"} logged today. The battery is only as good as your log.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    QuickAddRow()
                }
            }
        }
        item { WaterCard(Modifier.enter(3)) }
        item { CheckInCard(checkIns.orEmpty().size, Modifier.enter(3)) { v -> scope.launch { EnergyEngine.checkIn(ctx, v) } } }
        if (s.factors.isNotEmpty()) item { FactorsCard(s, Modifier.enter(3)) }
        item { ActivityLogCard(Modifier.enter(3)) }
        item {
            OutlinedButton(onClick = onMuscles, modifier = Modifier.fillMaxWidth().enter(3)) { Text("Muscle recovery & body map") }
        }
        itemsIndexed(s.gauges) { i, g ->
            Card(Modifier.fillMaxWidth().enter(4 + i), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Battery(g.level, Modifier.size(width = 30.dp, height = 48.dp), memoryKey = "gauge-${g.key}")
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row {
                            Text(g.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("${(g.level * 100).roundToInt()}%", style = MaterialTheme.typography.titleSmall, color = energyColor(g.level))
                        }
                        Text(g.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth().enter(10)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FuelPager(s, recentMeals.orEmpty())
                }
            }
        }
        if (s.lowNutrients.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth().enter(11)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Lowest this week", style = MaterialTheme.typography.titleSmall)
                    s.lowNutrients.forEachIndexed { i, (spec, ratio) ->
                        Row(Modifier.enter(i + 1, dx = 20.dp, dy = 0.dp)) {
                            Text(spec.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${(ratio * 100).roundToInt()}% of target", style = MaterialTheme.typography.bodyMedium, color = energyColor(ratio))
                        }
                    }
                    Text(
                        "Stored nutrients (iron, B12, vitamin D…) are judged over 14 days, quick-turnover ones (vitamin C, B vitamins) over 3.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            CollapsibleCard("How this is worked out", Modifier.enter(12)) {
                Text(
                    "Quick fuel is the sugar store in your liver (about 100 g). It drains about 4–5 g an hour between meals and refills as carbs are absorbed.\n\n" +
                        "Muscle fuel is the glycogen in your muscles (about 6 g per kg of body weight). Workouts use it, more at higher heart rate, and carbs refill it, fastest in the hours after training.\n\n" +
                        "Protein is today's intake against your target, adjusted for the time of day. Sleep and recovery come from your watch: hours slept and resting heart rate compared with your usual.\n\n" +
                        "Your check-ins teach the app how much each of these affects how you actually feel. Food values come from photo estimates, so treat the numbers as a good guide rather than a lab result.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** "What's affecting your energy": each factor as a bar growing left (down) or right (up), expo-out. */
@Composable
private fun FactorsCard(s: EnergyState, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("What's affecting your energy", style = MaterialTheme.typography.titleSmall)
            val maxPts = (s.factors.maxOfOrNull { kotlin.math.abs(it.second) } ?: 1.0).coerceAtLeast(5.0)
            s.factors.forEachIndexed { i, (label, pts) ->
                val grow = remember(label) { Animatable(0f) }
                LaunchedEffect(pts) { delay(i * Motion.STAGGER); grow.animateTo(1f, tween(Motion.SLOW, easing = Motion.ExpoOut)) }
                val good = pts >= 0
                val color = if (good) Color(0xFF2FBF71) else Color(0xFFE5484D)
                Column {
                    Row {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text((if (good) "+" else "−") + "${kotlin.math.abs(pts).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = color)
                    }
                    Canvas(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp)) {
                        val mid = size.width / 2
                        drawLine(color.copy(alpha = 0.15f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                        val len = (kotlin.math.abs(pts) / maxPts).toFloat() * mid * grow.value
                        drawLine(color, Offset(mid, size.height / 2), Offset(if (good) mid + len else mid - len, size.height / 2), size.height, StrokeCap.Round)
                    }
                }
            }
            Text(
                if (s.personalised) "Sizes learned from your check-ins." else "Starting estimates; your check-ins will tune them to you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ACTIVITY_TYPES = listOf("study" to "Study", "massage" to "Massage chair", "yoga" to "Yoga")

/** Log study, massage chair or yoga (ending now) in two taps; today's entries listed below with undo. */
@Composable
private fun ActivityLogCard(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf("study") }
    var minutes by remember { mutableStateOf(60) }
    val today = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val logged by rememberLoad("activities") { Db.get(ctx).activitiesSince(today) }
    val press = remember { MutableInteractionSource() }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Log what you did", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ACTIVITY_TYPES.forEach { (key, label) -> androidx.compose.material3.FilterChip(selected = type == key, onClick = { type = key; minutes = if (key == "study") 60 else 20 }, label = { Text(label) }) }
            }
            val options = if (type == "study") listOf(30, 60, 90, 120, 180) else listOf(10, 15, 20, 30, 45)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { m -> androidx.compose.material3.FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(if (m >= 60) "${m / 60}h${if (m % 60 > 0) " ${m % 60}" else ""}" else "${m}m") }) }
            }
            Button(onClick = { scope.launch { EnergyEngine.logActivity(ctx, type, minutes) } }, interactionSource = press, modifier = Modifier.pressScale(press)) {
                Text("Log ${ACTIVITY_TYPES.first { it.first == type }.second.lowercase()} (ending now)")
            }
            logged.orEmpty().reversed().forEachIndexed { i, a ->
                Row(Modifier.enter(i, dx = 16.dp, dy = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                    val t = Instant.ofEpochMilli(a.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                    Text(
                        "$t · ${ACTIVITY_TYPES.firstOrNull { it.first == a.type }?.second ?: a.type} · ${a.minutes} min",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { Db.get(ctx).deleteActivity(a.start) } }) { Text("Remove") }
                }
            }
        }
    }
}

/** "How energetic do you feel?" slider; the button morphs into a thank-you (back-out). */
@Composable
private fun CheckInCard(count: Int, modifier: Modifier, onSave: (Int) -> Unit) {
    var value by remember { mutableFloatStateOf(60f) }
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) { delay(2500); saved = false } }
    val press = remember { MutableInteractionSource() }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("How energetic do you feel?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${value.roundToInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = energyColor(value / 100.0))
            }
            Slider(value = value, onValueChange = { value = (it / 5).roundToInt() * 5f }, valueRange = 0f..100f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (count == 0) "Each answer helps it learn what drives your energy." else "$count check-ins so far.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                Button(onClick = { onSave(value.roundToInt()); saved = true }, enabled = !saved, interactionSource = press, modifier = Modifier.pressScale(press)) {
                    AnimatedContent(
                        targetState = saved,
                        transitionSpec = { (scaleIn(tween(Motion.BASE, easing = Motion.BackOut), initialScale = 0.6f) + fadeIn()).togetherWith(fadeOut(tween(Motion.QUICK))) },
                        label = "save",
                    ) { Text(if (it) "✓ Thanks" else "Save") }
                }
            }
        }
    }
}

/** Quick fuel and muscle fuel over 24 h; the lines draw in from left to right (expo-out). */
@Composable
internal fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text("  $text", style = MaterialTheme.typography.labelSmall)
    }
}
