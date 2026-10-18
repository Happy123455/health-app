package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.Dates
import com.happypipaliya.mealtracker.DigestionEngine
import com.happypipaliya.mealtracker.Meal
import com.happypipaliya.mealtracker.UiRefresh
import com.happypipaliya.sleepcore.Digestion
import com.happypipaliya.sleepcore.DigestionReport
import com.happypipaliya.sleepcore.DigestPhase
import com.happypipaliya.sleepcore.PairKind
import com.happypipaliya.sleepcore.Pairing
import kotlinx.coroutines.delay

private object DigestCache { var report: DigestionReport? = null }

/** Today's digestion; recomputed on data changes and once a minute so the bars move. */
@Composable
fun rememberDigestion(): DigestionReport? {
    val ctx = LocalContext.current
    val change by Db.changes.collectAsState()
    val resume by UiRefresh.ticks.collectAsState()
    var r by remember { mutableStateOf(DigestCache.report) }
    LaunchedEffect(change, resume) {
        while (true) {
            runCatching { DigestionEngine.report(ctx) }.getOrNull()?.let { r = it; DigestCache.report = it }
            delay(60_000)
        }
    }
    return r
}

@Composable
fun pairColor(kind: PairKind): Color = when (kind) {
    PairKind.GOOD -> energyColor(1.0)
    PairKind.TIP -> energyColor(0.5)
    PairKind.WARN -> energyColor(0.05)
}

/** Coloured badge: ✓ good pairing, → tip, ! warning. */
@Composable
private fun PairBadge(kind: PairKind) {
    val c = pairColor(kind)
    Box(Modifier.size(24.dp).clip(CircleShape).background(c.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        Text(when (kind) { PairKind.GOOD -> "✓"; PairKind.TIP -> "→"; PairKind.WARN -> "!" }, color = c, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun PairingRow(p: Pairing, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        PairBadge(p.kind)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, color = if (p.kind == PairKind.GOOD) MaterialTheme.colorScheme.onSurface else pairColor(p.kind))
            Text(p.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Today: one lane per meal still digesting. The solid part is time in the stomach, the lighter part
 * absorption; lanes grow in (expo-out) and the "now" line breathes (ease-in-out).
 */
@Composable
fun DigestionCard(modifier: Modifier) {
    val r = rememberDigestion() ?: return
    if (r.states.isEmpty() && r.now.isEmpty()) return
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Digestion", style = MaterialTheme.typography.titleMedium)
            r.now.take(2).forEachIndexed { i, p -> PairingRow(p, Modifier.enter(i, dx = 24.dp, dy = 0.dp)) }
            if (r.states.isNotEmpty()) DigestTimeline(r)
            Text("Timings are typical estimates; real digestion varies person to person.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DigestTimeline(r: DigestionReport) {
    val now = System.currentTimeMillis()
    val t0 = r.states.minOf { it.meal.time }
    val t1 = maxOf(r.states.maxOf { it.absorbedAt }, now + 30 * 60_000L)
    val span = (t1 - t0).toFloat()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(Motion.HERO, easing = Motion.ExpoOut)) }
    val breathe by rememberInfiniteTransition(label = "now").animateFloat(0.4f, 1f, infiniteRepeatable(tween(1200, easing = Motion.EaseInOut), RepeatMode.Reverse), label = "b")
    val stomach = MaterialTheme.colorScheme.primary
    val absorb = MaterialTheme.colorScheme.tertiary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val nowColor = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        r.states.forEach { s ->
            Column {
                Row {
                    Text(s.meal.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(
                        when (s.phase) {
                            DigestPhase.STOMACH -> "Stomach empty ~${Dates.time(s.stomachEmptyAt)}"
                            DigestPhase.ABSORBING -> "Absorbed by ~${Dates.time(s.absorbedAt)}"
                            DigestPhase.DONE -> "Digested"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (s.phase == DigestPhase.STOMACH) stomach else absorb,
                    )
                }
                Canvas(Modifier.fillMaxWidth().height(14.dp).padding(top = 3.dp)) {
                    val h = size.height
                    fun x(t: Long) = (t - t0) / span * size.width
                    drawRoundRect(track, Offset.Zero, Size(size.width, h), CornerRadius(h / 2))
                    val a = x(s.meal.time)
                    val e = a + (x(s.stomachEmptyAt) - a) * grow.value
                    val d = a + (x(s.absorbedAt) - a) * grow.value
                    drawRoundRect(absorb.copy(alpha = 0.45f), Offset(a, 0f), Size(d - a, h), CornerRadius(h / 2))
                    drawRoundRect(stomach, Offset(a, 0f), Size(e - a, h), CornerRadius(h / 2))
                    val n = x(now)
                    drawLine(nowColor.copy(alpha = breathe), Offset(n, -4f), Offset(n, h + 4f), 4f)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot("In stomach", stomach); LegendDot("Absorbing", absorb.copy(alpha = 0.6f)); LegendDot("Now", nowColor)
        }
    }
}

@Composable
private fun LegendDot(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(9.dp)) { drawCircle(color) }
        Text(" $text", style = MaterialTheme.typography.labelSmall)
    }
}

/** Meal page: digestion times for this meal and what it pairs well (or badly) with. */
@Composable
fun PairingCard(meal: Meal, modifier: Modifier) {
    val ctx = LocalContext.current
    val pairs by rememberLoad("pair-${meal.id}") { DigestionEngine.pairings(ctx, meal) }
    val d = remember(meal.id, meal.analysedAt) { DigestionEngine.digest(meal) } ?: return
    val s = Digestion.state(d, System.currentTimeMillis())
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Digestion & pairing", style = MaterialTheme.typography.titleSmall)
            Text(
                "Leaves your stomach ~${Dates.time(s.stomachEmptyAt)} · mostly absorbed by ~${Dates.time(s.absorbedAt)}" +
                    if (s.phase != DigestPhase.DONE) " (${s.phase.label.lowercase()} now)" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            pairs.orEmpty().forEachIndexed { i, p -> PairingRow(p, Modifier.enter(i, dx = 24.dp, dy = 0.dp)) }
            if (pairs?.isEmpty() == true) Text("Nothing notable to pair with this one.", style = MaterialTheme.typography.bodySmall)
            if (pairs.orEmpty().any { it.title == "Add lemon for iron" }) {
                val fruit = com.happypipaliya.mealtracker.FruitEngine.bestVitaminC(java.time.LocalDate.now().monthValue)
                if (fruit.isNotEmpty()) Text(
                    "In season now: " + fruit.joinToString(" or ") { "${it.name} (${it.local})" } + ". Have one with or right after this meal.",
                    style = MaterialTheme.typography.bodySmall, color = pairColor(PairKind.GOOD),
                )
            }
        }
    }
}
