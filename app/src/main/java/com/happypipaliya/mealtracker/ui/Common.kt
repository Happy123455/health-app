package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.Kind
import com.happypipaliya.mealtracker.NutrientDef
import com.happypipaliya.mealtracker.UiRefresh
import com.happypipaliya.mealtracker.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun MealTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Loads data off the main thread, and reloads after any database write or when the app resumes. */
@Composable
fun <T> rememberLoad(vararg keys: Any?, load: () -> T): State<T?> {
    val change by Db.changes.collectAsState()
    val resume by UiRefresh.ticks.collectAsState()
    val state = remember(*keys) { mutableStateOf<T?>(null) }
    val loader by rememberUpdatedState(load)
    LaunchedEffect(change, resume, *keys) { state.value = withContext(Dispatchers.IO) { loader() } }
    return state
}

@Composable
fun Header(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.enter(dx = 16.dp, dy = 0.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.enter(0, dy = 14.dp),
            )
            subtitle?.let {
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.enter(1, dy = 14.dp),
                )
            }
        }
        actions()
    }
}

/**
 * Label, amount and a bar that fills from empty (expo-out). [index] staggers a stack of bars.
 * Turns red (ease-in-out) when a "keep under" nutrient goes over target.
 */
@Composable
fun NutrientBar(def: NutrientDef, value: Double, target: Double, index: Int = 0, compact: Boolean = false, memoryKey: String? = null) {
    val fill = remember { Animatable(memoryKey?.let { MotionMemory.values[it] } ?: 0f) }
    val goal = if (target > 0) (value / target).toFloat().coerceIn(0f, 1f) else 0f
    LaunchedEffect(goal) {
        memoryKey?.let { MotionMemory.values[it] = goal }
        if (fill.value != goal) {
            delay(index.coerceAtMost(12) * Motion.STAGGER)
            fill.animateTo(goal, tween(Motion.SLOW + 200, easing = Motion.ExpoOut))
        }
    }
    val over = def.kind == Kind.LIMIT && value > target && target > 0
    val color by animateColorAsState(
        if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        tween(Motion.BASE, easing = Motion.EaseInOut), label = "bar",
    )
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(def.label, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (target > 0) "${fmt(value)} / ${fmt(target)} ${def.unit}" else "${fmt(value)} ${def.unit}",
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (target > 0) Canvas(Modifier.fillMaxWidth().padding(top = 5.dp).height(if (compact) 6.dp else 8.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = r)
            if (fill.value > 0f) drawRoundRect(color, size = Size(size.width * fill.value, size.height), cornerRadius = r)
        }
    }
}

fun scoreColor(score: Int): Color = when {
    score >= 8 -> Color(0xFF22A35A)
    score >= 5 -> Color(0xFFF59E0B)
    else -> Color(0xFFE5484D)
}

/** Health score as a ring that sweeps to score/10 and pops in with an elastic overshoot. */
@Composable
fun ScoreRing(score: Int, size: Dp = 44.dp, delayMs: Int = 150) {
    val color = scoreColor(score)
    val pop = rememberReveal(score, delayMs = delayMs)
    Box(
        Modifier.size(size).graphicsLayer {
            val s = 0.5f + 0.5f * pop
            scaleX = s; scaleY = s; alpha = pop.coerceIn(0f, 1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        ProgressRing(score / 10f, Modifier.fillMaxSize(), stroke = size / 11, colors = listOf(color), track = color.copy(alpha = 0.18f), delayMs = delayMs, durationMs = Motion.SLOW + 300)
        Text("$score", style = if (size > 50.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
    }
}

/** Big value over a small label. Numbers count up (expo-out). */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CountStat(label: String, value: Double?, suffix: String, modifier: Modifier = Modifier, delayMs: Int = 0, memoryKey: String? = null) {
    val shown = if (value == null) null else animateCount(value, delayMs, memoryKey = memoryKey)
    Stat(label, shown?.let { fmt(it.coerceAtLeast(0.0)) + suffix } ?: "–", modifier)
}

/**
 * Bar chart whose bars grow one after another and land with a bounce. The dashed target line
 * draws across (expo-out) once the bars are up.
 */
@Composable
fun BarChart(labels: List<String>, values: List<Double>, target: Double?, unit: String) {
    val barColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.outline
    val max = maxOf(values.maxOrNull() ?: 0.0, target ?: 0.0, 1.0)
    val n = values.size.coerceAtLeast(1)
    val perBar = 650f
    val stagger = 70f
    val total = perBar + stagger * (n - 1)
    val clock = remember { Animatable(0f) }
    val line = remember { Animatable(0f) }
    LaunchedEffect(values) {
        clock.snapTo(0f)
        line.snapTo(0f)
        clock.animateTo(total, tween(total.toInt(), easing = Motion.Linear))
        line.animateTo(1f, tween(Motion.SLOW, easing = Motion.ExpoOut))
    }
    fun barProgress(i: Int) = Motion.Bounce.transform(((clock.value - i * stagger) / perBar).coerceIn(0f, 1f))

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            values.forEachIndexed { i, v ->
                Text(
                    if (v > 0) fmt(v) else "", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1,
                    modifier = Modifier.weight(1f).graphicsLayer { alpha = ((clock.value - i * stagger - perBar * 0.6f) / 200f).coerceIn(0f, 1f) },
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 4.dp)) {
            val slot = size.width / n
            val barWidth = slot * 0.55f
            values.forEachIndexed { i, v ->
                val h = (v / max * size.height).toFloat() * barProgress(i)
                if (h > 0f) drawRoundRect(
                    color = barColor,
                    topLeft = Offset(i * slot + (slot - barWidth) / 2, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(8f, 8f),
                )
            }
            target?.takeIf { it > 0 }?.let {
                val y = size.height - (it / max * size.height).toFloat()
                if (line.value > 0f) drawLine(
                    lineColor, Offset(0f, y), Offset(size.width * line.value, y), strokeWidth = 3f, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        if (target != null && target > 0) Text(
            "Dashed line: your daily target (${fmt(target)} $unit)",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Card whose body opens and closes (ease-in-out) when you tap its title; the chevron turns over. */
@Composable
fun CollapsibleCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    startOpen: Boolean = false,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(Motion.BASE, easing = Motion.EaseInOut), label = "chevron")
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    subtitle?.let {
                        AnimatedContent(
                            targetState = it,
                            transitionSpec = { fadeIn(tween(Motion.BASE, easing = Motion.EaseInOut)).togetherWith(fadeOut(tween(Motion.QUICK))) },
                            label = "subtitle",
                        ) { text ->
                            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Icon(AppIcons.Chevron, if (open) "Collapse" else "Expand", modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(tween(Motion.BASE + 80, easing = Motion.EaseInOut)) + fadeIn(tween(Motion.BASE)),
                exit = shrinkVertically(tween(Motion.BASE, easing = Motion.EaseInOut)) + fadeOut(tween(Motion.QUICK)),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    content()
                }
            }
        }
    }
}

/** A few Material icons that aren't in the core icon set. */
object AppIcons {
    val Camera = icon(
        "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0" +
            "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9z" +
            "M12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z"
    )
    val Image = icon("M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z")
    val History = icon(
        "M13,3c-4.97,0 -9,4.03 -9,9L1,12l3.89,3.89 0.07,0.14L9,12L6,12c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7" +
            "c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9z" +
            "M12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08L13.5,8L12,8z"
    )
    val Chart = icon("M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z")
    val Sparkle = icon("M19,9l1.25,-2.75L23,5l-2.75,-1.25L19,1l-1.25,2.75L15,5l2.75,1.25L19,9zM11.5,9.5L9,4L6.5,9.5L1,12l5.5,2.5L9,20l2.5,-5.5L17,12l-5.5,-2.5zM19,15l-1.25,2.75L15,19l2.75,1.25L19,23l1.25,-2.75L23,19l-2.75,-1.25L19,15z")
    val Chevron = icon("M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z")

    private fun icon(path: String) = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
