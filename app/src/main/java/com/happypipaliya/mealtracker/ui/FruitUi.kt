package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.FruitEngine
import com.happypipaliya.mealtracker.Prefs
import com.happypipaliya.mealtracker.Targets
import com.happypipaliya.mealtracker.UiRefresh
import com.happypipaliya.sleepcore.Fruit
import com.happypipaliya.sleepcore.FruitGuide
import com.happypipaliya.sleepcore.FruitPick
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private object FruitCache { var report: FruitEngine.FruitReport? = null }

@Composable
fun rememberFruit(): FruitEngine.FruitReport? {
    val ctx = LocalContext.current
    val change by Db.changes.collectAsState()
    val resume by UiRefresh.ticks.collectAsState()
    var r by remember { mutableStateOf(FruitCache.report) }
    LaunchedEffect(change, resume) { runCatching { FruitEngine.report(ctx) }.getOrNull()?.let { r = it; FruitCache.report = it } }
    LaunchedEffect(Unit) { FruitEngine.fetchPrices(ctx) } // no-op if fetched in the last 6 h; saving bumps Db.changes
    return r
}

private fun rupees(v: Double) = "₹" + if (v >= 100) v.roundToInt().toString() else "%.0f".format(v)
private fun shortDate(iso: String) = LocalDate.parse(iso).let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.UK)}" }

/** Emoji where there is one, else a coloured disc with the first letter of the Gujarati name. */
@Composable
fun FruitBadge(f: Fruit, size: Dp = 40.dp) {
    val c = Color(f.color)
    Box(Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        if (f.emoji != null) Text(f.emoji!!, style = if (size >= 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
        else Text(f.local.take(1), color = c, fontWeight = FontWeight.Bold, style = if (size >= 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall)
    }
}

/** Today: the fruit to have next, with today's mandi price. */
@Composable
fun FruitCard(modifier: Modifier, onOpen: () -> Unit) {
    val r = rememberFruit() ?: return
    val pick = r.picks.firstOrNull() ?: return
    val press = remember { MutableInteractionSource() }
    Card(onClick = onOpen, interactionSource = press, shape = RoundedCornerShape(24.dp), modifier = modifier.fillMaxWidth().pressScale(press)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FruitBadge(pick.fruit, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Fruit for today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (pick.fruit.local == pick.fruit.name) pick.fruit.name else "${pick.fruit.name} (${pick.fruit.local})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    pick.price?.let { Text("  ${rupees(it.perKg)}/kg", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                }
                Text(pick.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
fun FruitScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = rememberFruit()
    var refreshing by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    val month = LocalDate.now().monthValue
    val monthName = Month.of(month).getDisplayName(TextStyle.FULL, Locale.UK)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header("Seasonal fruit", "Gujarat · $monthName · Rajkot mandi prices", onBack) }
        if (r == null) return@LazyColumn
        item { SectionTitle("Picked for you", Modifier.enter(1)) }
        itemsIndexed(r.picks.take(3)) { i, p ->
            PickCard(p, r.prices[p.fruit.key], open == p.fruit.key, Modifier.enter(2 + i, dx = 40.dp, dy = 0.dp, easing = Motion.BackOut)) {
                open = if (open == p.fruit.key) null else p.fruit.key
            }
        }
        item { SeasonCalendar(month, Modifier.enter(5)) }
        item {
            PriceBoard(r, month, Modifier.enter(6), refreshing) {
                scope.launch { refreshing = true; FruitEngine.fetchPrices(ctx, force = true); refreshing = false }
            }
        }
        FruitGuide.arriving(month).takeIf { it.isNotEmpty() }?.let { next ->
            item {
                Card(Modifier.fillMaxWidth().enter(7)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Arriving next month", style = MaterialTheme.typography.titleSmall)
                        next.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FruitBadge(f, 30.dp); Spacer(Modifier.width(10.dp))
                                Column { Text(if (f.local == f.name) f.name else "${f.name} (${f.local})", style = MaterialTheme.typography.bodyMedium); Text(f.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 4.dp))

@Composable
private fun PickCard(p: FruitPick, price: FruitEngine.PriceInfo?, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Card(onClick = onClick, interactionSource = press, modifier = modifier.fillMaxWidth().pressScale(press)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FruitBadge(p.fruit, 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (p.fruit.local == p.fruit.name) p.fruit.name else "${p.fruit.name} · ${p.fruit.local}", style = MaterialTheme.typography.titleSmall)
                    Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                price?.let {
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                        Text("${rupees(it.now.perKg)}/kg", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(it.now.market, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically(tween(Motion.BASE, easing = Motion.ExpoOut)) + fadeIn(), exit = shrinkVertically(tween(Motion.QUICK)) + fadeOut()) {
                ServingBars(p.fruit, Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** What one serving gives, as a share of your daily targets. */
@Composable
private fun ServingBars(f: Fruit, modifier: Modifier) {
    val ctx = LocalContext.current
    val targets = remember { Targets.forProfile(Prefs(ctx)) }
    val rows = listOf("vitamin_c_mg" to "Vitamin C", "fiber_g" to "Fibre", "potassium_mg" to "Potassium", "folate_mcg" to "Folate", "vitamin_a_mcg" to "Vitamin A", "magnesium_mg" to "Magnesium")
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(Motion.SLOW, easing = Motion.ExpoOut)) }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    val c = Color(f.color)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${f.serving} (~${f.servingG} g): ${FruitGuide.perServing(f, "calories_kcal").roundToInt()} kcal, ${FruitGuide.perServing(f, "carbs_g").roundToInt()} g carbs", style = MaterialTheme.typography.bodySmall)
        rows.forEach { (k, label) ->
            val t = targets[k] ?: return@forEach
            if (t <= 0) return@forEach
            val share = (FruitGuide.perServing(f, k) / t).coerceIn(0.0, 1.0).toFloat()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
                Canvas(Modifier.weight(1f).height(8.dp)) {
                    drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
                    if (share > 0) drawLine(c, Offset(0f, size.height / 2), Offset(size.width * share * grow.value, size.height / 2), size.height, StrokeCap.Round)
                }
                Text("${(share * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
            }
        }
        Text(f.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Twelve-month strip per fruit: light = in season, strong = peak. Sweeps in month by month; this month is outlined. */
@Composable
private fun SeasonCalendar(month: Int, modifier: Modifier) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(200); sweep.animateTo(12f, tween(Motion.HERO + 300, easing = Motion.EaseInOut)) }
    val fruits = FruitGuide.ALL.sortedWith(compareBy<Fruit> { month !in it.months }.thenBy { f -> (1..12).first { ((month - 1 + it - 1) % 12 + 1) in f.months || it == 12 } })
    val outline = MaterialTheme.colorScheme.onSurface
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Season calendar", style = MaterialTheme.typography.titleSmall)
            Row {
                Spacer(Modifier.width(96.dp))
                "JFMAMJJASOND".forEachIndexed { i, ch ->
                    Text(ch.toString(), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                        fontWeight = if (i + 1 == month) FontWeight.Bold else FontWeight.Normal)
                }
            }
            fruits.forEach { f ->
                val c = Color(f.color)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.width(96.dp),
                        color = if (month in f.months) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    Canvas(Modifier.weight(1f).height(14.dp)) {
                        val w = size.width / 12
                        for (m in 1..12) {
                            val shown = (sweep.value - (m - 1)).coerceIn(0f, 1f)
                            val col = when {
                                m in f.peak && m in f.months -> c
                                m in f.months -> c.copy(alpha = 0.4f)
                                else -> empty
                            }
                            drawRoundRect(col.copy(alpha = col.alpha * shown), Offset((m - 1) * w + 1.5f, 0f), Size(w - 3f, size.height), CornerRadius(4f))
                            if (m == month) drawRoundRect(outline, Offset((m - 1) * w + 0.5f, -1f), Size(w - 1f, size.height + 2f), CornerRadius(5f), style = Stroke(2f))
                        }
                    }
                }
            }
            Text("Light: in season · strong: peak (best and cheapest)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Today's wholesale price per fruit with its min-max range and a sparkline once a few days are saved. */
@Composable
private fun PriceBoard(r: FruitEngine.FruitReport, month: Int, modifier: Modifier, refreshing: Boolean, onRefresh: () -> Unit) {
    val rows = FruitGuide.ALL.mapNotNull { f -> r.prices[f.key]?.let { f to it } }.sortedBy { (f, _) -> month !in f.months }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Mandi prices", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !refreshing) { Text(if (refreshing) "Updating…" else "Update") }
            }
            if (rows.isEmpty()) Text(
                if (r.fetchedAt == 0L) "Fetching today's prices…" else "No fruit prices reported by Gujarat markets yet today.",
                style = MaterialTheme.typography.bodySmall,
            )
            rows.forEachIndexed { i, (f, p) ->
                Row(Modifier.enter(i, dx = 24.dp, dy = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                    FruitBadge(f, 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${p.now.market}${if (p.local) "" else " (Gujarat)"} · ${shortDate(p.now.date)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (p.history.size >= 2) Sparkline(p.history.map { it.second }, Modifier.width(48.dp).height(20.dp))
                    Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
                        Text("${rupees(p.now.perKg)}/kg", style = MaterialTheme.typography.titleSmall)
                        RangeBar(p.now.minKg, p.now.perKg, p.now.maxKg, Color(f.color), Modifier.width(80.dp).height(8.dp))
                        Text("${rupees(p.now.minKg)}–${rupees(p.now.maxKg)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("Wholesale prices from Agmarknet (data.gov.in); shops usually charge more. Rajkot district markets first, else elsewhere in Gujarat.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RangeBar(min: Double, modal: Double, max: Double, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val slide = remember { Animatable(0f) }
    LaunchedEffect(modal) { slide.animateTo(1f, tween(Motion.SLOW, easing = Motion.BackOut)) }
    Canvas(modifier) {
        drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f, StrokeCap.Round)
        val x = if (max > min) ((modal - min) / (max - min)).toFloat() * size.width else size.width / 2
        drawCircle(color, size.height / 2, Offset(x * slide.value, size.height / 2))
    }
}

@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
    val up = values.last() > values.first()
    val c = if (up) energyColor(0.2) else energyColor(1.0) // rising price = worse
    Canvas(modifier.padding(end = 6.dp)) {
        val lo = values.min(); val hi = values.max().coerceAtLeast(lo + 0.01)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(size.width * i / (values.size - 1), size.height * (1 - ((v - lo) / (hi - lo)).toFloat()))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, c, style = Stroke(3f, cap = StrokeCap.Round))
    }
}
