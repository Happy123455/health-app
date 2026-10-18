package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.EnergyEngine
import com.happypipaliya.mealtracker.Prefs
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val GLASS_ML = 250.0

/**
 * Water logged in Meal Tracker (Samsung Health keeps its water log to itself). One glass icon per
 * 250 ml of your target (~35 ml per kg); new glasses fill with a back-out pour.
 */
@Composable
fun WaterCard(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val today by rememberLoad("water-$dayStart") { Db.get(ctx).waterBetween(dayStart, System.currentTimeMillis() + 60_000) }
    val list = today.orEmpty()
    val drunk = list.sumOf { it.second }
    val target = (Prefs(ctx).weightKg ?: 65.0) * 35
    val glasses = ceil(target / GLASS_ML).toInt().coerceIn(4, 14)
    val water = Color(0xFF4FC3F7)
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Water", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${animateCount(drunk, memoryKey = "water-ml").roundToInt()} / ${target.roundToInt()} ml",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = water)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(glasses) { i ->
                    val fill by animateFloatAsState(((drunk - i * GLASS_ML) / GLASS_ML).toFloat().coerceIn(0f, 1f),
                        tween(Motion.SLOW, delayMillis = (i % 4) * 40, easing = Motion.BackOut), label = "glass")
                    Glass(fill, water, outline, Modifier.size(width = 22.dp, height = 30.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { scope.launch { EnergyEngine.logWater(ctx, GLASS_ML) } }) { Text("+ Glass") }
                FilledTonalButton(onClick = { scope.launch { EnergyEngine.logWater(ctx, 500.0) } }) { Text("+ 500 ml") }
                if (list.isNotEmpty()) TextButton(onClick = { scope.launch { EnergyEngine.undoWater(ctx, list.last().first) } }) { Text("Undo") }
            }
        }
    }
}

/** A tapered glass; water fills from the bottom with a slight tilt as it settles. */
@Composable
private fun Glass(fill: Float, water: Color, outline: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val glass = Path().apply {
            moveTo(0f, 0f); lineTo(w, 0f); lineTo(w * 0.82f, h); lineTo(w * 0.18f, h); close()
        }
        if (fill > 0.01f) clipPath(glass) {
            val top = h * (1 - fill.coerceIn(0f, 1.1f))
            val tilt = (1 - fill.coerceIn(0f, 1f)) * h * 0.08f
            val p = Path().apply { moveTo(0f, top + tilt); lineTo(w, top - tilt); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(p, water.copy(alpha = 0.85f))
        }
        drawPath(glass, outline, style = Stroke(2.5f))
    }
}
