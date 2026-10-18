package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.happypipaliya.mealtracker.Analysis
import com.happypipaliya.mealtracker.Dates
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.Kind
import com.happypipaliya.mealtracker.Meal
import com.happypipaliya.mealtracker.MealCapture
import com.happypipaliya.mealtracker.Nutrients
import com.happypipaliya.mealtracker.Prefs
import com.happypipaliya.mealtracker.Status
import com.happypipaliya.mealtracker.Targets
import com.happypipaliya.mealtracker.Upgrade
import com.happypipaliya.mealtracker.Work
import com.happypipaliya.mealtracker.fmt
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@Composable
fun MealScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val loaded by rememberLoad(id) { Db.get(ctx).meal(id) }
    val m = loaded ?: return
    val a = m.analysis
    val targets = remember { Targets.forProfile(Prefs(ctx)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Header(m.title(), "${Dates.label(m.date)}, ${Dates.time(m.takenAt)}", onBack) {
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.enter(2, dy = 0.dp, dx = (-16).dp)) {
                    Icon(Icons.Filled.Delete, "Remove meal")
                }
            }
        }
        item {
            // Parallax: the photo drifts and fades a little slower than the page scrolls.
            AsyncImage(
                model = m.photoPath?.let(::File),
                contentDescription = m.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .sharedPhoto("photo-${m.id}")
                    .fillMaxWidth()
                    .height(300.dp)
                    .graphicsLayer {
                        val offset = if (listState.firstVisibleItemIndex == 1) listState.firstVisibleItemScrollOffset.toFloat() else 0f
                        translationY = offset * 0.4f
                        alpha = 1f - (offset / size.height.coerceAtLeast(1f)) * 0.6f
                    }
                    .clip(RoundedCornerShape(24.dp)),
            )
        }
        if (m.status != Status.DONE) item { StatusCard(m, Modifier.enter(1)) }
        if (a != null && a.isFood) {
            item { ScoreRow(a, Modifier.enter(1)) }
            if (m.status == Status.DONE) item { EatAgainCard(m, Modifier.enter(2)) {} }
            item { MacroGrid(a, Modifier.enter(2)) }
            if (m.status == Status.DONE) item { PairingCard(m, Modifier.enter(3)) }
            if (a.upgrades.isNotEmpty()) {
                item { SectionTitle("Make it better", Modifier.enter(3)) }
                itemsIndexed(a.upgrades) { i, u -> UpgradeCard(u, Modifier.enter(4 + i, dx = 48.dp, dy = 0.dp, easing = Motion.BackOut)) }
            }
            if (a.highlights.isNotEmpty() || a.watchOuts.isNotEmpty()) item { GoodAndWatch(a, Modifier.enter(6)) }
            if (a.items.isNotEmpty()) item { ItemsCard(a, Modifier.enter(7)) }
            item { MicrosCard(a, targets, Modifier.enter(8)) }
            if (a.assumptions.isNotBlank()) item {
                Text(
                    "How this was estimated: ${a.assumptions}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.enter(9),
                )
            }
        }
        item { NoteCard(m, Modifier.enter(10)) }
        item { Footer(m) }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove this meal?") },
        text = { Text("It will be removed from Meal Tracker and Samsung Health. The original photo stays in your Gallery.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                MealCapture.delete(ctx, m)
                onBack()
            }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** While waiting: shimmering placeholder lines and a pulsing dot. Otherwise: what went wrong. */
@Composable
private fun StatusCard(m: Meal, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (m.status == Status.PENDING || m.status == Status.ANALYSING) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot()
                    Spacer(Modifier.width(8.dp))
                    Text("Gemini is looking at this meal…", style = MaterialTheme.typography.bodyMedium)
                }
                listOf(1f, 0.8f, 0.55f).forEach { w ->
                    Box(
                        Modifier.fillMaxWidth(w).height(12.dp).clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .shimmer(),
                    )
                }
            } else {
                Text(
                    when (m.status) {
                        Status.NOT_FOOD -> "This doesn't look like a meal, so it was skipped. If it is food, describe it below and re-analyse."
                        Status.FAILED -> "Couldn't analyse this yet (${m.error}). " +
                            if (m.attempts >= 6) "Tap Re-analyse to try again." else "It will retry automatically."
                        else -> m.status
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ScoreRow(a: Analysis, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ScoreRing(a.healthScore, size = 60.dp, delayMs = 250)
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Health score", style = MaterialTheme.typography.titleSmall)
            Text(
                "${a.mealType.replaceFirstChar { it.titlecase(Locale.UK) }} · ${a.confidence} confidence",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MacroGrid(a: Analysis, modifier: Modifier) {
    val n = a.nutrients
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                CountStat("Calories", n["calories_kcal"], " kcal", Modifier.weight(1f))
                CountStat("Protein", n["protein_g"], " g", Modifier.weight(1f), delayMs = 60)
                CountStat("Carbs", n["carbs_g"], " g", Modifier.weight(1f), delayMs = 120)
            }
            Row(Modifier.fillMaxWidth()) {
                CountStat("Fat", n["fat_g"], " g", Modifier.weight(1f), delayMs = 180)
                CountStat("Fibre", n["fiber_g"], " g", Modifier.weight(1f), delayMs = 240)
                CountStat("Sugar", n["sugar_g"], " g", Modifier.weight(1f), delayMs = 300)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 4.dp))
}

/** Tap to reveal why it helps (expands with ease-in-out; the chevron turns over). */
@Composable
private fun UpgradeCard(u: Upgrade, modifier: Modifier) {
    var open by rememberSaveable(u.title) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(Motion.BASE, easing = Motion.EaseInOut), label = "chevron")
    val interaction = remember { MutableInteractionSource() }
    val on = MaterialTheme.colorScheme.onTertiaryContainer
    Card(
        onClick = { open = !open },
        interactionSource = interaction,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = on, modifier = Modifier.weight(1f))
                Icon(AppIcons.Chevron, if (open) "Hide why" else "Show why", tint = on, modifier = Modifier.graphicsLayer { rotationZ = turn })
            }
            Text(u.how, style = MaterialTheme.typography.bodyMedium, color = on)
            AnimatedVisibility(
                visible = open,
                enter = expandVertically(tween(Motion.BASE, easing = Motion.EaseInOut)) + fadeIn(tween(Motion.BASE)),
                exit = shrinkVertically(tween(Motion.BASE, easing = Motion.EaseInOut)) + fadeOut(tween(Motion.QUICK)),
            ) {
                Text(u.why, style = MaterialTheme.typography.bodySmall, color = on, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun GoodAndWatch(a: Analysis, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (a.highlights.isNotEmpty()) {
                Text("What's good", style = MaterialTheme.typography.titleSmall)
                a.highlights.forEachIndexed { i, h -> Text("✓  $h", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.enter(i + 1, dx = 20.dp, dy = 0.dp)) }
            }
            if (a.watchOuts.isNotEmpty()) {
                Text("Watch out for", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                a.watchOuts.forEachIndexed { i, w -> Text("!  $w", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.enter(i + 4, dx = 20.dp, dy = 0.dp)) }
            }
        }
    }
}

@Composable
private fun ItemsCard(a: Analysis, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("What's on the plate", style = MaterialTheme.typography.titleSmall)
            a.items.forEachIndexed { i, item ->
                Row(Modifier.enter(i + 1, dx = 20.dp, dy = 0.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${item.portion} (about ${fmt(item.grams)} g)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("${fmt(item.kcal)} kcal · ${fmt(item.protein)} g P", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MicrosCard(a: Analysis, targets: Map<String, Double>, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Vitamins & minerals (vs your daily target)", style = MaterialTheme.typography.titleSmall)
            Nutrients.all.filter { it.kind == Kind.MICRO || it.kind == Kind.LIMIT }.forEachIndexed { i, n ->
                NutrientBar(n, a.nutrients[n.key] ?: 0.0, targets[n.key] ?: 0.0, index = i)
            }
        }
    }
}

/** Correction box; the button morphs into a confirmation (back-out) when tapped. */
@Composable
private fun NoteCard(m: Meal, modifier: Modifier) {
    val ctx = LocalContext.current
    var note by rememberSaveable(m.id) { mutableStateOf(m.note.orEmpty()) }
    var queued by remember { mutableStateOf(false) }
    LaunchedEffect(m.note) { if (note.isEmpty()) note = m.note.orEmpty() }
    LaunchedEffect(queued) { if (queued) { delay(2200); queued = false } }
    val interaction = remember { MutableInteractionSource() }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Something off?", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Add details, e.g. \"2 rotis, no ghee, half the rice\"") },
                minLines = 2,
            )
            Button(
                onClick = {
                    Db.get(ctx).requeue(m.id, note)
                    Work.scanNow(ctx)
                    queued = true
                },
                enabled = !queued,
                interactionSource = interaction,
                modifier = Modifier.pressScale(interaction),
            ) {
                AnimatedContent(
                    targetState = queued,
                    transitionSpec = {
                        (scaleIn(tween(Motion.BASE, easing = Motion.BackOut), initialScale = 0.6f) + fadeIn(tween(Motion.QUICK)))
                            .togetherWith(fadeOut(tween(Motion.QUICK)))
                    },
                    label = "reanalyse",
                ) { done -> Text(if (done) "✓ Re-analysing" else "Re-analyse") }
            }
        }
    }
}

@Composable
private fun Footer(m: Meal) {
    val parts = listOfNotNull(
        m.model?.let { "Analysed by $it" },
        m.analysedAt?.let { "at ${Dates.time(it)}" },
        if (m.lat != null && m.lon != null) String.format(Locale.UK, "· location %.4f, %.4f", m.lat, m.lon) else null,
    )
    if (parts.isNotEmpty()) Text(
        parts.joinToString(" "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
