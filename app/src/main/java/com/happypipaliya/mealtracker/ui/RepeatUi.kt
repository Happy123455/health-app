package com.happypipaliya.mealtracker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.happypipaliya.mealtracker.Dates
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.Meal
import com.happypipaliya.mealtracker.MealCapture
import com.happypipaliya.mealtracker.fmt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val PORTIONS = listOf(0.5 to "½×", 1.0 to "1×", 1.5 to "1.5×")

/** Bottom sheet: pick a meal you've had before (most eaten first) and log it again without a photo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepeatMealSheet(onDismiss: () -> Unit, onAdded: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val favourites by rememberLoad("favourites") { Db.get(ctx).favourites() }
    var portion by remember { mutableDoubleStateOf(1.0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Eat it again", style = MaterialTheme.typography.titleLarge)
            Text("Pick a meal you've had before. It's logged now with the same nutrients, no photo needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Portion", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
                PORTIONS.forEach { (p, label) -> FilterChip(selected = portion == p, onClick = { portion = p }, label = { Text(label) }) }
            }
            val list = favourites.orEmpty()
            if (favourites != null && list.isEmpty()) Text("No meals with photos yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(list, key = { _, f -> f.first.id }) { i, (meal, count) ->
                    FavouriteRow(meal, count, portion, Modifier.enter(i)) {
                        scope.launch {
                            val id = MealCapture.repeat(ctx, meal, portion)
                            sheet.hide()
                            onDismiss()
                            if (id > 0) onAdded(id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavouriteRow(meal: Meal, count: Int, portion: Double, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Card(onClick = onClick, interactionSource = press, shape = RoundedCornerShape(18.dp), modifier = modifier.fillMaxWidth().pressScale(press)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (meal.thumbPath == null) Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
            else AsyncImage(File(meal.thumbPath), null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meal.name ?: "Meal", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${fmt((meal.calories ?: 0.0) * portion)} kcal · ${fmt((meal.protein ?: 0.0) * portion)} g protein · " +
                        if (count > 1) "eaten $count×" else "last ${Dates.label(meal.date)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(AppIcons.History, "Add again", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** On a meal's page: log this same meal again now, with a portion choice; confirms with a pop (back-out). */
@Composable
fun EatAgainCard(meal: Meal, modifier: Modifier, onAdded: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(done) { if (done != null) { delay(2500); done = null } }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AnimatedContent(
                targetState = done ?: "Eating this again?",
                transitionSpec = { (scaleIn(tween(Motion.BASE, easing = Motion.BackOut), initialScale = 0.8f) + fadeIn()).togetherWith(fadeOut(tween(Motion.QUICK))) },
                label = "again",
            ) { Text(it, style = MaterialTheme.typography.titleSmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PORTIONS.forEach { (p, label) ->
                    val press = remember { MutableInteractionSource() }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val id = MealCapture.repeat(ctx, meal, p)
                                if (id > 0) { done = "✓ Logged again for now"; onAdded(id) }
                            }
                        },
                        enabled = done == null,
                        interactionSource = press,
                        modifier = Modifier.pressScale(press, 0.92f),
                    ) { Text(if (p == 1.0) "Same again" else label) }
                }
            }
        }
    }
}
