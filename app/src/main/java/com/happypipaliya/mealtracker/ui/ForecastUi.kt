package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.happypipaliya.mealtracker.Dates
import com.happypipaliya.mealtracker.DigestionEngine
import com.happypipaliya.mealtracker.Meal
import com.happypipaliya.sleepcore.Digestion
import com.happypipaliya.sleepcore.EnergyState
import com.happypipaliya.sleepcore.FuelPoint
import com.happypipaliya.sleepcore.MealDigest
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HOUR = 3_600_000L
private const val STEP = 10 * 60_000L

private fun mealLabel(t: Long): String {
    val h = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).hour
    return when {
        h < 11 -> "Usual breakfast"
        h < 16 -> "Usual lunch"
        h < 19 -> "Usual snack"
        else -> "Usual dinner"
    }
}

/** A tappable moment on the timeline. [series]: 0 quick fuel, 1 muscle fuel, 2 stomach. */
private data class Mark(val time: Long, val series: Int, val value: Double, val title: String, val detail: String, val future: Boolean)

/**
 * 48 hours on one connected timeline: the last 24 h (solid) runs into the next 24 h (dotted) at "Now".
 * Three series: quick (liver) fuel, muscle fuel and stomach fullness (shaded, from the digestion model),
 * for logged meals and the meals you usually have. Swipe through it or jump with Past / Now / Next; tap
 * a dot for what happened when, or anywhere else for the values at that moment.
 */
@Composable
fun FuelPager(s: EnergyState, meals: List<Meal>) {
    val past = s.timeline
    if (past.size < 2) return
    val future = s.forecast
    val now = past.last().time
    val t0 = past.first().time
    val t1 = maxOf(future.lastOrNull()?.time ?: now, now + HOUR)
    val span = (t1 - t0).toFloat()

    val eaten = remember(meals) { meals.mapNotNull { m -> DigestionEngine.digest(m)?.let { m to it } } }
    val planned = remember(s.plannedFoods) {
        s.plannedFoods.map { f ->
            val fat = f.micros["fat_g"] ?: (f.carbs * 0.3)
            MealDigest(f.time, mealLabel(f.time), emptyList(), f.carbs, f.protein, fat, f.fiber,
                f.micros["calories_kcal"] ?: (f.carbs * 4 + f.protein * 4 + fat * 9), f.micros)
        }
    }
    // Back-to-back sessions (e.g. several routines in one go) become one block.
    val workouts = remember(s.plannedExercises) {
        s.plannedExercises.sortedBy { it.start }.fold(mutableListOf<Pair<Long, Long>>()) { acc, e ->
            val last = acc.lastOrNull()
            if (last != null && e.start - last.second <= 45 * 60_000L) acc[acc.size - 1] = last.first to maxOf(last.second, e.end) else acc += e.start to e.end
            acc
        }
    }
    val digests = remember(eaten, planned) { eaten.map { it.second } + planned }
    val stomach = remember(digests, t0, t1) { (t0..t1 step STEP).map { it to Digestion.fullness(digests, it) } }
    fun fuelAt(t: Long): FuelPoint {
        val list = if (t <= now) past else future
        return list.minByOrNull { abs(it.time - t) } ?: past.last()
    }

    val marks = remember(s, digests, workouts) {
        buildList {
            eaten.filter { it.second.time in t0..t1 }.forEach { (m, d) ->
                add(Mark(d.time, 0, fuelAt(d.time).liver, "Ate ${d.name}", "${Dates.time(d.time)} · ${d.carbs.roundToInt()} g carbs, ${d.protein.roundToInt()} g protein", false))
                val empty = Digestion.stomachEmptyAt(d)
                if (empty in t0..t1) add(Mark(empty, 2, 0.0, "Stomach empty", "~${Dates.time(empty)} · ${d.name} has moved on to be absorbed", empty > now))
            }
            planned.forEach { d ->
                add(Mark(d.time, 0, fuelAt(d.time).liver, d.name, "~${Dates.time(d.time)} · about ${d.carbs.roundToInt()} g carbs, ${d.protein.roundToInt()} g protein", true))
                val empty = Digestion.stomachEmptyAt(d)
                if (empty <= t1) add(Mark(empty, 2, 0.0, "Stomach empty", "~${Dates.time(empty)} after your ${d.name.removePrefix("Usual ").lowercase()}", true))
            }
            past.minByOrNull { it.liver }?.let { add(Mark(it.time, 0, it.liver, "Lowest quick fuel", "${Dates.time(it.time)} · ${(it.liver * 100).roundToInt()}%", false)) }
            future.minByOrNull { it.liver }?.let { add(Mark(it.time, 0, it.liver, "Lowest quick fuel (forecast)", "~${Dates.time(it.time)} · ${(it.liver * 100).roundToInt()}%" + (planned.firstOrNull { p -> p.time > it.time }?.let { p -> ", before your ${p.name.removePrefix("Usual ").lowercase()}" } ?: ""), true)) }
            past.maxByOrNull { it.liver }?.let { add(Mark(it.time, 0, it.liver, "Highest quick fuel", "${Dates.time(it.time)} · ${(it.liver * 100).roundToInt()}%", false)) }
            workouts.forEach { (a, b) -> add(Mark(a, 1, fuelAt(a).muscle, "Workout (like last week)", "${Dates.time(a)}–${Dates.time(b)} · uses muscle fuel", true)) }
            add(Mark(now, 0, past.last().liver, "Now", "Quick ${(past.last().liver * 100).roundToInt()}% · muscle ${(past.last().muscle * 100).roundToInt()}% · stomach ${(Digestion.fullness(digests, now) * 100).roundToInt()}% full", false))
        }
    }

    var picked by remember { mutableStateOf<Mark?>(null) }
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(Motion.HERO + 600, easing = Motion.ExpoOut)) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(picked) { pop.snapTo(0f); pop.animateTo(1f, tween(Motion.BASE, easing = Motion.BackOut)) }

    val liverColor = MaterialTheme.colorScheme.primary
    val muscleColor = MaterialTheme.colorScheme.tertiary
    val stomachColor = Color(0xFFFFB74D)
    val mealColor = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val ink = MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = sub)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val viewW = maxWidth
            val totalW = viewW * 2
            val viewPx = with(density) { viewW.toPx() }
            val scroll = rememberScrollState()
            // Start with "now" about two-thirds across, so both sides of the join are visible.
            LaunchedEffect(viewPx) { scroll.scrollTo((viewPx * 0.35f).toInt()) }
            Column {
                TimelineTabs(scroll, viewPx) { target -> scope.launch { scroll.animateScrollTo(target, tween(Motion.SLOW, easing = Motion.EaseInOut)) } }
                Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                    Column(Modifier.width(totalW)) {
                        // Meal pins: photos for meals eaten, dashed 🍽 for usual meals ahead.
                        BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp)) {
                            val w = maxWidth
                            eaten.filter { it.second.time in t0..t1 }.forEach { (m, d) ->
                                val x = w * ((d.time - t0) / span)
                                Box(
                                    Modifier.offset(x = (x - 15.dp).coerceIn(0.dp, w - 30.dp)).size(30.dp)
                                        .graphicsLayer { scaleX = draw.value; scaleY = draw.value }
                                        .clip(CircleShape).background(mealColor.copy(alpha = 0.25f)).border(2.dp, mealColor, CircleShape)
                                        .clickable { picked = marks.firstOrNull { it.time == d.time && it.series == 0 } },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val thumb = m.thumbPath ?: m.photoPath
                                    if (thumb != null && File(thumb).exists()) AsyncImage(File(thumb), d.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                    else Text(d.name.take(1).uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                            planned.forEach { d ->
                                val x = w * ((d.time - t0) / span)
                                Box(
                                    Modifier.offset(x = (x - 15.dp).coerceIn(0.dp, w - 30.dp)).size(30.dp)
                                        .graphicsLayer { scaleX = draw.value; scaleY = draw.value }
                                        .clip(CircleShape).border(2.dp, mealColor.copy(alpha = 0.6f), CircleShape)
                                        .clickable { picked = marks.firstOrNull { it.time == d.time && it.series == 0 } },
                                    contentAlignment = Alignment.Center,
                                ) { Text("🍽", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        Canvas(
                            Modifier.fillMaxWidth().height(170.dp).pointerInput(marks) {
                                detectTapGestures { p ->
                                    val chartH = size.height - 22.dp.toPx()
                                    fun x(t: Long) = (t - t0) / span * size.width
                                    fun y(m: Mark) = chartH * (1f - m.value.toFloat())
                                    val near = marks.minByOrNull { (Offset(x(it.time), y(it)) - p).getDistance() }
                                    picked = if (near != null && (Offset(x(near.time), y(near)) - p).getDistance() < 28.dp.toPx()) near else {
                                        val t = t0 + (p.x / size.width * span).toLong()
                                        val f = fuelAt(t)
                                        Mark(t, -1, 0.0, if (t > now) "Forecast ~${Dates.time(t)}" else Dates.time(t),
                                            "Quick ${(f.liver * 100).roundToInt()}% · muscle ${(f.muscle * 100).roundToInt()}% · stomach ${(Digestion.fullness(digests, t) * 100).roundToInt()}% full", t > now)
                                    }
                                }
                            },
                        ) {
                            val chartH = size.height - 22.dp.toPx()
                            fun x(t: Long) = (t - t0) / span * size.width
                            fun y(v: Double) = chartH * (1f - v.toFloat())
                            val reveal = size.width * draw.value
                            for (g in 0..4) drawLine(grid, Offset(0f, chartH * g / 4), Offset(size.width, chartH * g / 4), 2f)
                            // Future half gets a faint wash so the join at "now" reads clearly.
                            drawRect(ink.copy(alpha = 0.03f), Offset(x(now), 0f), Size(size.width - x(now), chartH))
                            workouts.forEach { (a, b) ->
                                drawRect(muscleColor.copy(alpha = 0.14f), Offset(x(a), 0f), Size((x(b) - x(a)).coerceAtLeast(3f), chartH))
                            }
                            // Stomach fullness: shaded area, solid edge before now, dotted after.
                            val area = Path().apply {
                                moveTo(0f, chartH)
                                stomach.forEach { (t, v) -> if (x(t) <= reveal) lineTo(x(t), y(v * 0.9)) }
                                lineTo(minOf(reveal, size.width), chartH); close()
                            }
                            drawPath(area, stomachColor.copy(alpha = 0.18f))
                            fun series(points: List<Pair<Long, Double>>, c: Color, dotted: Boolean, width: Float = 5f) {
                                val path = Path()
                                var started = false
                                points.forEach { (t, v) ->
                                    if (x(t) > reveal) return@forEach
                                    if (!started) { path.moveTo(x(t), y(v)); started = true } else path.lineTo(x(t), y(v))
                                }
                                drawPath(path, c, style = Stroke(width, cap = StrokeCap.Round, pathEffect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(4f, 12f)) else null))
                            }
                            val stomachPast = stomach.filter { it.first <= now }.map { it.first to it.second * 0.9 }
                            val stomachNext = stomach.filter { it.first >= now - STEP }.map { it.first to it.second * 0.9 }
                            series(stomachPast, stomachColor, false, 3f)
                            series(stomachNext, stomachColor, true, 3f)
                            series(past.map { it.time to it.muscle }, muscleColor, false)
                            series((listOf(past.last()) + future).map { it.time to it.muscle }, muscleColor, true)
                            series(past.map { it.time to it.liver }, liverColor, false)
                            series((listOf(past.last()) + future).map { it.time to it.liver }, liverColor, true)
                            // Now line.
                            drawLine(ink.copy(alpha = 0.6f), Offset(x(now), 0f), Offset(x(now), chartH), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                            // Marks.
                            val colors = listOf(liverColor, muscleColor, stomachColor)
                            marks.forEach { m ->
                                val px = x(m.time)
                                if (px > reveal) return@forEach
                                val c = Offset(px, y(if (m.series == 2) 0.0 else m.value))
                                val col = colors[m.series]
                                if (m.future) {
                                    drawCircle(surface, 8f, c); drawCircle(col, 8f, c, style = Stroke(3f))
                                } else {
                                    drawCircle(col, 8f, c); drawCircle(surface, 3.5f, c)
                                }
                            }
                            // Selected: guide line and halo.
                            picked?.let { m ->
                                val px = x(m.time)
                                drawLine(ink.copy(alpha = 0.5f), Offset(px, 0f), Offset(px, chartH), 2f)
                                if (m.series >= 0) drawCircle(colors[m.series].copy(alpha = 0.3f), 20f * pop.value, Offset(px, y(if (m.series == 2) 0.0 else m.value)))
                            }
                            // Hour labels every 3 h along the bottom.
                            val firstHour = (t0 / (3 * HOUR) + 1) * 3 * HOUR
                            var t = firstHour
                            while (t < t1) {
                                val label = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).let { "%02d:00".format(it.hour) }
                                val r = measurer.measure(label, labelStyle)
                                drawText(r, topLeft = Offset((x(t) - r.size.width / 2).coerceIn(0f, size.width - r.size.width), chartH + 4.dp.toPx()))
                                t += 3 * HOUR
                            }
                            val nowR = measurer.measure("Now", labelStyle.copy(color = ink, fontWeight = FontWeight.Bold))
                            drawText(nowR, topLeft = Offset(x(now) + 4.dp.toPx(), 2.dp.toPx()))
                        }
                    }
                }
            }
        }
        AnimatedContent(
            targetState = picked,
            transitionSpec = { (fadeIn(tween(Motion.BASE)) + slideInVertically(tween(Motion.BASE, easing = Motion.ExpoOut)) { it / 2 }).togetherWith(fadeOut(tween(Motion.QUICK))) },
            label = "picked",
        ) { m ->
            if (m == null) Text("Tap a dot to see when it happens, or anywhere on the chart for the values at that time.",
                style = MaterialTheme.typography.bodySmall, color = sub)
            else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ink.copy(alpha = 0.06f)).padding(10.dp)) {
                Text(m.title + if (m.future && m.series >= 0) " · forecast" else "", style = MaterialTheme.typography.titleSmall)
                Text(m.detail, style = MaterialTheme.typography.bodySmall, color = sub)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend("Quick fuel", liverColor)
            Legend("Muscle fuel", muscleColor)
            Legend("Stomach", stomachColor)
        }
        Text("Solid: what happened · dotted: forecast if you eat at your usual times and repeat last week's workout. Stomach = how full it is while digesting.",
            style = MaterialTheme.typography.labelSmall, color = sub)
    }
}

/** Past / Now / Next shortcuts; the highlighted one follows the scroll position. */
@Composable
private fun TimelineTabs(scroll: ScrollState, viewPx: Float, go: (Int) -> Unit) {
    val targets = listOf("Last 24 h" to 0, "Now" to (viewPx * 0.5f).toInt(), "Next 24 h" to scroll.maxValue)
    val pos = scroll.value
    val active = targets.indices.minBy { abs(targets[it].second - pos) }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Fuel & digestion", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        targets.forEachIndexed { i, (label, target) ->
            val on = i == active
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent, tween(Motion.BASE), label = "tab")
            Text(
                label, style = MaterialTheme.typography.labelMedium,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { go(target) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
