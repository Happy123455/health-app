package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.MuscleEngine
import com.happypipaliya.mealtracker.UiRefresh
import com.happypipaliya.mealtracker.fmt
import com.happypipaliya.sleepcore.Muscle
import com.happypipaliya.sleepcore.MuscleReport
import com.happypipaliya.sleepcore.MuscleState
import kotlin.math.roundToInt

private object MuscleCache { var report: MuscleReport? = null }

@Composable
fun rememberMuscles(): MuscleReport? {
    val ctx = LocalContext.current
    val change by Db.changes.collectAsState()
    val resume by UiRefresh.ticks.collectAsState()
    var r by remember { mutableStateOf(MuscleCache.report) }
    LaunchedEffect(change, resume) { runCatching { MuscleEngine.compute(ctx) }.getOrNull()?.let { r = it; MuscleCache.report = it } }
    return r
}

/** One muscle drawn as an ellipse in a 200 x 400 body space. */
private data class Region(val muscle: Muscle, val cx: Float, val cy: Float, val rx: Float, val ry: Float) {
    fun contains(x: Float, y: Float): Boolean {
        val dx = (x - cx) / rx
        val dy = (y - cy) / ry
        return dx * dx + dy * dy <= 1.2f
    }
}

private fun pair(m: Muscle, cx: Float, cy: Float, rx: Float, ry: Float) =
    listOf(Region(m, 100 - cx, cy, rx, ry), Region(m, 100 + cx, cy, rx, ry))

private val FRONT = pair(Muscle.SHOULDERS, 40f, 80f, 14f, 12f) + pair(Muscle.CHEST, 18f, 99f, 17f, 13f) +
    pair(Muscle.BICEPS, 50f, 118f, 9f, 18f) + pair(Muscle.FOREARMS, 56f, 158f, 7.5f, 19f) +
    listOf(Region(Muscle.CORE, 100f, 142f, 17f, 30f)) +
    pair(Muscle.QUADS, 15f, 226f, 13f, 36f) + pair(Muscle.CALVES, 14f, 300f, 8.5f, 24f)

private val BACK = pair(Muscle.SHOULDERS, 40f, 80f, 14f, 12f) + listOf(Region(Muscle.UPPER_BACK, 100f, 100f, 26f, 22f)) +
    pair(Muscle.UPPER_BACK, 20f, 126f, 10f, 15f) + pair(Muscle.TRICEPS, 50f, 118f, 9f, 18f) +
    pair(Muscle.FOREARMS, 56f, 158f, 7.5f, 19f) + listOf(Region(Muscle.LOWER_BACK, 100f, 153f, 13f, 14f)) +
    pair(Muscle.GLUTES, 12f, 186f, 13f, 13f) + pair(Muscle.HAMSTRINGS, 14f, 229f, 12f, 32f) + pair(Muscle.CALVES, 14f, 300f, 10f, 24f)

/** Grey body silhouette under the muscles. */
private fun DrawScope.silhouette(c: Color) {
    drawCircle(c, 17f, Offset(100f, 35f))
    drawRoundRect(c, Offset(93f, 48f), Size(14f, 16f), CornerRadius(4f))
    drawRoundRect(c, Offset(68f, 62f), Size(64f, 116f), CornerRadius(22f))
    drawRoundRect(c, Offset(73f, 162f), Size(54f, 40f), CornerRadius(14f))
    for (s in listOf(-1f, 1f)) {
        val x = 100 + s * 50
        drawRoundRect(c, Offset(x - 11, 70f), Size(22f, 72f), CornerRadius(11f))
        drawRoundRect(c, Offset(x + s * 5 - 9, 136f), Size(18f, 46f), CornerRadius(9f))
        drawCircle(c, 7f, Offset(x + s * 6, 190f))
        drawRoundRect(c, Offset(100 + s * 15 - 14, 188f), Size(28f, 80f), CornerRadius(14f))
        drawRoundRect(c, Offset(100 + s * 14 - 10, 262f), Size(20f, 76f), CornerRadius(10f))
        drawOval(c, Offset(100 + s * 14 - 11, 336f), Size(22f, 10f))
    }
}

private fun muscleColor(s: MuscleState?, neutral: Color): Color =
    if (s == null || (s.lastTrained == null && s.recovery >= 1.0)) neutral else energyColor(s.recovery)

/**
 * Body map. Muscles are coloured red (just trained) → amber → green (ready); recovering ones breathe
 * (ease-in-out); particles carry protein (blue), carbs (amber) and vitamin C (orange) from the plate to
 * them (linear loops, denser when you've eaten enough). Flips front/back with a 3D turn (ease-in-out).
 */
@Composable
fun BodyMap(
    report: MuscleReport,
    back: Boolean,
    modifier: Modifier,
    selected: Muscle? = null,
    particles: Boolean = true,
    onSelect: (Muscle?) -> Unit = {},
) {
    val flip = remember { Animatable(if (back) 180f else 0f) }
    LaunchedEffect(back) { flip.animateTo(if (back) 180f else 0f, tween(Motion.SLOW, easing = Motion.EaseInOut)) }
    val showBack = flip.value > 90f
    val regions = if (showBack) BACK else FRONT
    val states = report.states.associateBy { it.muscle }
    val loop = rememberInfiniteTransition(label = "body")
    val pulse by loop.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1100, easing = Motion.EaseInOut), RepeatMode.Reverse), label = "pulse")
    val flow by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = Motion.Linear)), label = "flow")
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(Motion.HERO, easing = Motion.ExpoOut)) }
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val body = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val outline = MaterialTheme.colorScheme.onSurface
    val protein = report.repair.firstOrNull { it.key == "protein" }?.level ?: 0.0
    val nutrientColors = listOf(Color(0xFF4F8CFF), Color(0xFFF5B83D), Color(0xFFFF8A3D))
    Canvas(
        modifier
            .graphicsLayer { rotationY = flip.value; cameraDistance = 14 * density }
            .pointerInput(showBack) {
                detectTapGestures { p ->
                    val s = minOf(size.width / 200f, size.height / 400f)
                    val ox = (size.width - 200 * s) / 2
                    val x = (p.x - ox) / s
                    val y = p.y / s
                    // Mirror taps on the back view (the canvas is turned 180° around Y).
                    val bx = if (showBack) 200 - x else x
                    onSelect(regions.firstOrNull { it.contains(bx, y) }?.muscle)
                }
            },
    ) {
        val s = minOf(size.width / 200f, size.height / 400f)
        translate(left = (size.width - 200 * s) / 2) {
            scale(s, pivot = Offset.Zero) {
                // Draw the back view mirrored so it reads correctly after the 180° turn.
                scale(if (showBack) -1f else 1f, 1f, pivot = Offset(100f, 200f)) {
                    silhouette(body)
                    regions.forEach { r ->
                        val st = states[r.muscle]
                        val recovering = st != null && st.recovery < 0.95
                        val alpha = (if (recovering) pulse else 1f) * reveal.value
                        val base = muscleColor(st, neutral)
                        drawOval(base.copy(alpha = base.alpha * alpha.coerceIn(0f, 1f)), Offset(r.cx - r.rx, r.cy - r.ry), Size(r.rx * 2, r.ry * 2))
                        if (r.muscle == selected) drawOval(outline, Offset(r.cx - r.rx, r.cy - r.ry), Size(r.rx * 2, r.ry * 2), style = Stroke(2.2f))
                    }
                    if (particles) {
                        // The plate.
                        val plate = Offset(100f, 386f)
                        drawOval(outline.copy(alpha = 0.25f), Offset(plate.x - 18, plate.y - 5), Size(36f, 10f))
                        val targets = regions.filter { (states[it.muscle]?.recovery ?: 1.0) < 0.95 }
                        val perTarget = 2 + (protein * 5).roundToInt()
                        targets.forEachIndexed { ti, r ->
                            for (i in 0 until perTarget) {
                                val t = (flow + i.toFloat() / perTarget + ti * 0.17f) % 1f
                                val end = Offset(r.cx, r.cy)
                                val ctrl = Offset((plate.x + end.x) / 2 + (if (r.cx < 100) -45f else 45f), (plate.y + end.y) / 2)
                                val u = 1 - t
                                val pos = Offset(
                                    u * u * plate.x + 2 * u * t * ctrl.x + t * t * end.x,
                                    u * u * plate.y + 2 * u * t * ctrl.y + t * t * end.y,
                                )
                                val a = (if (t < 0.15f) t / 0.15f else if (t > 0.85f) (1 - t) / 0.15f else 1f) * reveal.value
                                drawCircle(nutrientColors[(i + ti) % 3].copy(alpha = a * 0.9f), 2.6f, pos)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Today: small body map with what's recovering. */
@Composable
fun MuscleCard(modifier: Modifier, onOpen: () -> Unit) {
    val r = rememberMuscles() ?: return
    val press = remember { MutableInteractionSource() }
    Card(onClick = onOpen, interactionSource = press, shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth().pressScale(press)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            BodyMap(r, back = false, modifier = Modifier.size(width = 70.dp, height = 140.dp), particles = r.recovering.isNotEmpty())
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Muscles", style = MaterialTheme.typography.titleMedium)
                if (r.recovering.isEmpty()) Text("All recovered and ready", style = MaterialTheme.typography.bodyMedium, color = energyColor(1.0))
                r.recovering.take(3).forEach {
                    Text("${it.muscle.label} · ${(it.recovery * 100).roundToInt()}% recovered", style = MaterialTheme.typography.bodyMedium, color = energyColor(it.recovery))
                }
                Text(r.tip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
        }
    }
}

@Composable
fun MuscleScreen(onBack: () -> Unit) {
    val r = rememberMuscles()
    var back by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Muscle?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header("Muscles", "Recovery from your workouts, food and sleep", onBack) }
        if (r == null) return@LazyColumn
        item { TipCard("Right now", r.tip, Modifier.enter(1)) }
        item {
            Card(Modifier.fillMaxWidth().enter(2), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !back, onClick = { back = false; selected = null }, label = { Text("Front") })
                        FilterChip(selected = back, onClick = { back = true; selected = null }, label = { Text("Back") })
                    }
                    BodyMap(r, back, Modifier.fillMaxWidth().height(380.dp).padding(top = 8.dp), selected) { selected = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Legend("Just trained", energyColor(0.05)); Legend("Recovering", energyColor(0.5)); Legend("Ready", energyColor(1.0))
                    }
                    Text("Tap a muscle. Dots show protein, carbs and vitamin C from your food heading to recovering muscles.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        item {
            AnimatedContent(
                targetState = selected,
                transitionSpec = { (fadeIn(tween(Motion.BASE)) + slideInVertically(tween(Motion.SLOW, easing = Motion.ExpoOut)) { it / 3 }).togetherWith(fadeOut(tween(Motion.QUICK))) },
                label = "muscle",
            ) { m ->
                val st = r.states.firstOrNull { it.muscle == m }
                if (m != null && st != null) MuscleDetail(st) else RecoveringList(r)
            }
        }
        item { RepairKit(r, Modifier.enter(4)) }
        item { DeskVsWeek(r, Modifier.enter(5)) }
        item {
            CollapsibleCard("How recovery is worked out", Modifier.enter(6)) {
                Text(
                    "Each workout's name tells the app which muscles it trained (your routines are named by muscle group). How hard it was comes from its length and heart rate.\n\n" +
                        "Recovery takes about 18 hours after light work and up to ~60 hours after a hard session. Beginners typically need around 48 hours after a solid workout. " +
                        "It's slower when protein since the workout is low or sleep was short, and a little faster after the massage chair.\n\n" +
                        "Nothing on your wrist can measure muscle damage, so this is an estimate from training, food and sleep.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun MuscleDetail(st: MuscleState) {
    val now = System.currentTimeMillis()
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRingPct(st.recovery)
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(st.muscle.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        st.readyAt != null -> "Ready in about ${((st.readyAt!! - now) / 3_600_000.0).roundToInt().coerceAtLeast(1)} h"
                        st.lastTrained != null -> "Recovered"
                        else -> "Not trained this week"
                    },
                    style = MaterialTheme.typography.bodyMedium, color = energyColor(st.recovery),
                )
                st.lastTrained?.let {
                    Text("Last trained ${com.happypipaliya.mealtracker.Dates.label(com.happypipaliya.mealtracker.Dates.dateOf(it))}, ${com.happypipaliya.mealtracker.Dates.time(it)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Desk days use it ${usageWord(st.dailyUse)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun usageWord(u: Double) = when {
    u >= 0.6 -> "a lot"
    u >= 0.35 -> "a fair amount"
    else -> "very little"
}

/** Percentage ring (expo-out sweep) coloured by recovery. */
@Composable
private fun ScoreRingPct(level: Double, size: Dp = 58.dp) {
    val c = energyColor(level)
    androidx.compose.foundation.layout.Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        ProgressRing(level.toFloat(), Modifier.fillMaxSize(), stroke = 5.dp, colors = listOf(c), track = c.copy(alpha = 0.18f))
        Text("${(level * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = c)
    }
}

@Composable
private fun RecoveringList(r: MuscleReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (r.recovering.isEmpty()) "Everything is recovered" else "Recovering", style = MaterialTheme.typography.titleSmall)
            r.recovering.forEachIndexed { i, st ->
                Row(Modifier.enter(i, dx = 20.dp, dy = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(st.muscle.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${(st.recovery * 100).roundToInt()}%", style = MaterialTheme.typography.bodyMedium, color = energyColor(st.recovery))
                }
            }
        }
    }
}

/** What muscle repair needs today vs what you've eaten; bars fill in sequence. */
@Composable
private fun RepairKit(r: MuscleReport, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Repair kit (today)", style = MaterialTheme.typography.titleSmall)
            r.repair.forEachIndexed { i, n ->
                val fill = remember(n.key) { Animatable(0f) }
                LaunchedEffect(n.level) {
                    kotlinx.coroutines.delay(i * Motion.STAGGER)
                    fill.animateTo(n.level.toFloat(), tween(Motion.SLOW + 200, easing = Motion.ExpoOut))
                }
                val c = energyColor(n.level)
                val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                Column {
                    Row {
                        Text(n.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${fmt(n.have)} / ${fmt(n.need)} ${n.unit}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Canvas(Modifier.fillMaxWidth().height(7.dp).padding(top = 3.dp)) {
                        drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                        if (fill.value > 0f) drawLine(c, Offset(0f, size.height / 2), Offset(size.width * fill.value, size.height / 2), size.height, StrokeCap.Round)
                    }
                    Text(n.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Grey bar: how much a desk/study day uses each muscle. Colour bar: your training this week. */
@Composable
private fun DeskVsWeek(r: MuscleReport, modifier: Modifier) {
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(Motion.HERO, easing = Motion.ExpoOut)) }
    val maxWeek = (r.states.maxOfOrNull { it.weekLoad } ?: 1.0).coerceAtLeast(1.0)
    val grey = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val primary = MaterialTheme.colorScheme.primary
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Desk day vs your training this week", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Legend("Used on a desk day", grey); Legend("Trained this week", primary) }
            r.states.sortedBy { it.dailyUse + it.weekLoad / maxWeek }.forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.muscle.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
                    Canvas(Modifier.weight(1f).height(16.dp)) {
                        val h = size.height / 2 - 1
                        drawRoundRect(grey, Offset.Zero, Size(size.width * st.dailyUse.toFloat() * grow.value, h), CornerRadius(h / 2))
                        drawRoundRect(primary, Offset(0f, h + 2), Size(size.width * (st.weekLoad / maxWeek).toFloat() * grow.value, h), CornerRadius(h / 2))
                    }
                }
            }
            if (r.focus.isNotEmpty()) {
                Text("Worth training", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                r.focus.forEach { (m, why) -> Text("${m.label}: $why", style = MaterialTheme.typography.bodySmall) }
            }
            Text("Desk-day use is general guidance for study days, not a measurement.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
