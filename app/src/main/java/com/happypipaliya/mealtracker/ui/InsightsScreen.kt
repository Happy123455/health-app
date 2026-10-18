package com.happypipaliya.mealtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happypipaliya.mealtracker.Dates
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.InsightsAi
import com.happypipaliya.mealtracker.Kind
import com.happypipaliya.mealtracker.Nutrients
import com.happypipaliya.mealtracker.Prefs
import com.happypipaliya.mealtracker.Targets
import com.happypipaliya.mealtracker.fmt
import com.happypipaliya.mealtracker.objects
import com.happypipaliya.mealtracker.strings
import com.happypipaliya.mealtracker.sumNutrients
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

@Composable
fun InsightsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val from = today.minusDays(6)
    val days = (0..6).map { from.plusDays(it.toLong()).toString() }
    val meals by rememberLoad("insights-meals") { Db.get(ctx).doneMealsBetween(from.toString(), today.toString()) }
    val activity by rememberLoad("insights-activity") { Db.get(ctx).activityBetween(from.toString(), today.toString()) }
    val insight by rememberLoad("insight") { Db.get(ctx).latestInsight() }
    val targets = remember { Targets.forProfile(Prefs(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val mealList = meals.orEmpty()
    val perDay = days.map { d -> sumNutrients(mealList.filter { it.date == d }) }
    val logged = days.indices.filter { i -> mealList.any { it.date == days[i] } }
    val labels = days.map { Dates.weekdayShort(it).take(2) }
    val acts = activity.orEmpty().associateBy { it.date }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header("Insights", "Last 7 days · ${logged.size} days with meals") }
        item {
            ChartCard("Calories", Modifier.enter(1)) {
                BarChart(labels, perDay.map { it["calories_kcal"] ?: 0.0 }, targets["calories_kcal"], "kcal")
            }
        }
        item {
            ChartCard("Protein", Modifier.enter(2)) {
                BarChart(labels, perDay.map { it["protein_g"] ?: 0.0 }, targets["protein_g"], "g")
            }
        }
        if (logged.isNotEmpty()) item {
            // Average over days that have meals, as % of target: lowest first.
            val avg = Nutrients.all.associate { n -> n.key to logged.sumOf { perDay[it][n.key] ?: 0.0 } / logged.size }
            Card(Modifier.fillMaxWidth().enter(3)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Daily average vs target", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Lowest first. These are photo estimates, so look at trends rather than exact numbers.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Nutrients.all
                        .filter { it.kind == Kind.MACRO || it.kind == Kind.MICRO }
                        .sortedBy { n -> (avg[n.key] ?: 0.0) / (targets[n.key] ?: 1.0).coerceAtLeast(0.001) }
                        .forEachIndexed { i, n -> NutrientBar(n, avg[n.key] ?: 0.0, targets[n.key] ?: 0.0, index = i) }
                    Text("Keep these under target", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    Nutrients.all.filter { it.kind == Kind.LIMIT }.forEachIndexed { i, n -> NutrientBar(n, avg[n.key] ?: 0.0, targets[n.key] ?: 0.0, index = 8 + i) }
                }
            }
        }
        item {
            ChartCard("Steps", Modifier.enter(4)) {
                BarChart(labels, days.map { (acts[it]?.steps ?: 0L).toDouble() }, null, "steps")
                val workouts = acts.values.flatMap { it.workouts }
                Text(
                    "${workouts.size} workouts this week" +
                        if (workouts.isNotEmpty()) ", ${workouts.sumOf { it.minutes }} minutes in total" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item { Box(Modifier.enter(5)) { WakeLearningCard() } }
        item {
            Card(Modifier.fillMaxWidth().enter(6)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Patterns and plan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Gemini looks at your last ${InsightsAi.DAYS} days of meals and watch data, finds patterns, " +
                            "and suggests a week of tasty fixes.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val press = remember { MutableInteractionSource() }
                        Button(enabled = !busy, interactionSource = press, modifier = Modifier.pressScale(press), onClick = {
                            busy = true
                            error = null
                            scope.launch {
                                InsightsAi.generate(ctx).onFailure { error = it.message ?: "Something went wrong" }
                                busy = false
                            }
                        }) { Text(if (insight == null) "Find my patterns" else "Refresh") }
                        if (busy) CircularProgressIndicator(Modifier.padding(start = 16.dp).size(24.dp), strokeWidth = 3.dp)
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    insight?.let {
                        Text(
                            "Last updated ${Dates.label(Dates.dateOf(it.createdAt))}, ${Dates.time(it.createdAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // While Gemini thinks: shimmering placeholder cards.
        if (busy) items(3) { i ->
            Card(Modifier.fillMaxWidth().enter(i)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(0.5f, 1f, 0.85f).forEach { w ->
                        Box(
                            Modifier.fillMaxWidth(w).height(12.dp).clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).shimmer(),
                        )
                    }
                }
            }
        }
        // Results cascade in; keyed on the time so a refresh replays the reveal.
        if (!busy) insight?.let { ins -> item { key(ins.createdAt) { InsightBody(ins.json) } } }
    }
}

@Composable
private fun ChartCard(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InsightBody(j: JSONObject) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        j.optString("one_small_change").takeIf { it.isNotBlank() }?.let {
            // The headline tip pops in with an elastic overshoot.
            val pop = rememberReveal(it, delayMs = 100)
            TipCard("One small change", it, Modifier.graphicsLayer { val s = 0.85f + 0.15f * pop; scaleX = s; scaleY = s; alpha = pop.coerceIn(0f, 1f) })
        }
        Card(Modifier.fillMaxWidth().enter(1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(j.optString("summary"), style = MaterialTheme.typography.bodyMedium)
                j.optJSONArray("patterns").objects().forEachIndexed { i, it ->
                    Column(Modifier.enter(i + 2, dx = 24.dp, dy = 0.dp)) {
                        Text(it.optString("title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(it.optString("detail"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        val wins = j.optJSONArray("wins").strings()
        if (wins.isNotEmpty()) Card(Modifier.fillMaxWidth().enter(3)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Going well", style = MaterialTheme.typography.titleSmall)
                wins.forEach { Text("✓  $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        j.optJSONArray("focus").objects().forEachIndexed { i, it ->
            Card(Modifier.fillMaxWidth().enter(4 + i, dx = 40.dp, dy = 0.dp, easing = Motion.BackOut)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Focus: ${it.optString("nutrient")}", style = MaterialTheme.typography.titleSmall)
                    Text(it.optString("why"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Try: " + it.optJSONArray("foods").strings().joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        val plan = j.optJSONArray("plan").objects()
        if (plan.isNotEmpty()) Card(Modifier.fillMaxWidth().enter(7)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Next 7 days", style = MaterialTheme.typography.titleSmall)
                plan.forEachIndexed { i, it ->
                    Row(Modifier.enter(i + 1, dx = 20.dp, dy = 0.dp)) {
                        Text(it.optString("day").take(3), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 12.dp))
                        Text(it.optString("idea"), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
