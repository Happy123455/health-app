package com.happypipaliya.mealtracker.wear

import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private data class Favourite(val id: Long, val name: String, val kcal: Int, val protein: Int, val count: Int)

private val PORTIONS = listOf(0.5, 1.0, 1.5, 2.0)

/** "Eat again" on the wrist: pick a favourite meal, set the portion with the bezel, log it on the phone. */
class RepeatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RepeatFlow(onDone = { finish() }) } }
    }
}

@Composable
private fun RepeatFlow(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val favourites = remember {
        WatchPrefs(ctx).favourites.lines().mapNotNull { l ->
            val p = l.split('|')
            if (p.size < 5) null else Favourite(p[0].toLong(), p[1], p[2].toInt(), p[3].toInt(), p[4].toInt())
        }
    }
    var picked by remember { mutableStateOf<Favourite?>(null) }
    var sent by remember { mutableStateOf<Pair<Favourite, Double>?>(null) }
    LaunchedEffect(sent) { if (sent != null) { delay(1200); onDone() } }
    AnimatedContent(
        targetState = Triple(picked, sent, favourites.isEmpty()),
        transitionSpec = { (fadeIn(tween(300)) + scaleIn(tween(450, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)), initialScale = 0.9f)).togetherWith(fadeOut(tween(150))) },
        label = "repeat",
    ) { (p, s, empty) ->
        when {
            s != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("✓ Logged\n${s.first.name}", textAlign = TextAlign.Center, style = MaterialTheme.typography.title3, modifier = Modifier.padding(20.dp))
            }
            empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Open Meal Tracker on your phone once to sync your meals.", textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            }
            p == null -> FavouriteList(favourites) { picked = it }
            else -> PortionPicker(p) { portion ->
                val now = System.currentTimeMillis()
                watchScope.launch {
                    runCatching {
                        val req = PutDataMapRequest.create("/repeat/$now").apply {
                            dataMap.putLong("meal", p.id)
                            dataMap.putDouble("portion", portion)
                            dataMap.putLong("time", now)
                        }.asPutDataRequest().setUrgent()
                        Wearable.getDataClient(ctx).putDataItem(req).await()
                    }
                }
                sent = p to portion
            }
        }
    }
}

@Composable
private fun FavouriteList(items: List<Favourite>, onPick: (Favourite) -> Unit) {
    val state = rememberScalingLazyListState(initialCenterItemIndex = 0)
    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(Modifier.fillMaxWidth(), state = state, autoCentering = AutoCenteringParams(itemIndex = 0)) {
            item { ListHeader { Text("Eat again") } }
            items(items) { f ->
                Chip(
                    onClick = { onPick(f) },
                    icon = { MealPhoto(f.id, 40.dp) },
                    label = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text("${f.kcal} kcal" + if (f.count > 1) " · ${f.count}×" else "") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Turn the bezel through ½×, 1×, 1.5×, 2×; calories update as it rolls; tap Log. */
@Composable
private fun PortionPicker(f: Favourite, onLog: (Double) -> Unit) {
    var index by remember { mutableIntStateOf(1) }
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    fun step(d: Int) {
        val next = (index + d).coerceIn(0, PORTIONS.lastIndex)
        if (next != index) {
            index = next
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    Box(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent { e -> if (e.verticalScrollPixels != 0f) step(if (e.verticalScrollPixels > 0) 1 else -1); true }
            .focusRequester(focus).focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
            MealPhoto(f.id, 56.dp)
            Text(f.name, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(tween(260)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(200)))
                        .togetherWith(slideOutVertically(tween(200)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(150)))
                },
                label = "portion",
            ) { i ->
                val p = PORTIONS[i]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (p == 0.5) "½×" else "${p.toString().removeSuffix(".0")}×", style = MaterialTheme.typography.display2)
                    Text("${(f.kcal * p).toInt()} kcal · ${(f.protein * p).toInt()} g protein", style = MaterialTheme.typography.caption2)
                }
            }
            Button(onClick = { onLog(PORTIONS[index]) }) { Text("Log") }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Round meal photo sent from the phone; a soft placeholder until it arrives. */
@Composable
private fun MealPhoto(id: Long, size: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val bitmap = remember(id) {
        java.io.File(ctx.filesDir, "thumbs/$id.jpg").takeIf { it.exists() }
            ?.let { android.graphics.BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }
    if (bitmap != null) androidx.compose.foundation.Image(
        bitmap, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape),
    ) else Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colors.surface))
}
