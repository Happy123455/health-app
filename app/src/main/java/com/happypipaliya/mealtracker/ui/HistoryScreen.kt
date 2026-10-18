package com.happypipaliya.mealtracker.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.happypipaliya.mealtracker.DaySummary
import com.happypipaliya.mealtracker.Dates
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.fmt
import java.io.File

@Composable
fun HistoryScreen(onDay: (String) -> Unit) {
    val ctx = LocalContext.current
    val days by rememberLoad("history") { Db.get(ctx).days(limit = 120) }
    val list = days.orEmpty()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header("History", if (list.isEmpty()) null else "${list.size} days with meals") }
        if (days != null && list.isEmpty()) item {
            Text(
                "Your analysed meals will appear here, day by day.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.enter(1),
            )
        }
        itemsIndexed(list, key = { _, d -> d.date }) { i, d ->
            DayCard(d, Modifier.animateItem().enter(i + 1)) { onDay(d.date) }
        }
    }
}

@Composable
private fun DayCard(d: DaySummary, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Dates.label(d.date), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${d.meals} meal${if (d.meals == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${fmt(animateCount(d.kcal, durationMs = Motion.SLOW))} kcal", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${fmt(d.protein)} g protein · ${fmt(d.fiber)} g fibre",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.thumbs.take(5).forEachIndexed { i, path ->
                    // Thumbnails drop in one after another and land with a bounce.
                    val p = rememberReveal(path, delayMs = 120 + i * 70, durationMs = 650, easing = Motion.Bounce)
                    AsyncImage(
                        model = File(path), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp)
                            .graphicsLayer { translationY = (1f - p) * -40f; alpha = p.coerceIn(0f, 1f) }
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
        }
    }
}
