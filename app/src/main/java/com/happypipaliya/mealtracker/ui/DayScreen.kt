package com.happypipaliya.mealtracker.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.health.connect.client.PermissionController
import coil.compose.AsyncImage
import com.happypipaliya.mealtracker.ActivityDay
import com.happypipaliya.mealtracker.Dates
import com.happypipaliya.mealtracker.Db
import com.happypipaliya.mealtracker.HealthSync
import com.happypipaliya.mealtracker.Importer
import com.happypipaliya.mealtracker.Meal
import com.happypipaliya.mealtracker.MealCapture
import com.happypipaliya.mealtracker.Nutrients
import com.happypipaliya.mealtracker.Prefs
import com.happypipaliya.mealtracker.Status
import com.happypipaliya.mealtracker.Targets
import com.happypipaliya.mealtracker.UiRefresh
import com.happypipaliya.mealtracker.Work
import com.happypipaliya.mealtracker.Workout
import com.happypipaliya.mealtracker.fmt
import com.happypipaliya.mealtracker.fmtInt
import com.happypipaliya.mealtracker.sumNutrients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(date: String, isToday: Boolean, onMeal: (Long) -> Unit, onBack: (() -> Unit)?, onEnergy: () -> Unit = {}, onMuscles: () -> Unit = {}, onFruit: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val meals by rememberLoad(date) { Db.get(ctx).mealsOn(date) }
    val activity by rememberLoad("activity", date) { Db.get(ctx).activity(date) }
    val resume by UiRefresh.ticks.collectAsState()
    val targets = remember(resume) { Targets.forProfile(Prefs(ctx)) }
    var connected by remember { mutableStateOf(false) }
    LaunchedEffect(resume) { connected = HealthSync.connected(ctx) }
    var refreshing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val list = meals.orEmpty()
    val done = list.filter { it.status == Status.DONE }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    Work.scanNow(ctx)
                    withContext(Dispatchers.IO) { runCatching { HealthSync.refreshDay(ctx, LocalDate.parse(date)) } }
                    delay(700)
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Header(if (isToday) greeting() else Dates.label(date), Dates.full(date), onBack) }
                if (isToday) item { SetupCard(onConnected = { connected = true }) }
                if (isToday) item { EnergyCard(Modifier.enter(1), onEnergy) }
                if (isToday) item { WaterCard(Modifier.enter(1)) }
                if (isToday) item { MuscleCard(Modifier.enter(2), onMuscles) }
                if (isToday) item { DigestionCard(Modifier.enter(2)) }
                if (isToday) item { FruitCard(Modifier.enter(2), onFruit) }
                item { HeroCard(date, done, targets, Modifier.enter(2)) }
                if (isToday) {
                    done.lastOrNull()?.analysis?.nextMeal?.takeIf { it.isNotBlank() }?.let { tip ->
                        item { TipCard("What to eat next", tip, Modifier.enter(2)) }
                    }
                    item { Box(Modifier.enter(3)) { WakeCard() } }
                }
                item { ActivityCard(date, activity, connected, Modifier.enter(4)) }
                item {
                    Row(Modifier.padding(top = 4.dp).enter(5), verticalAlignment = Alignment.CenterVertically) {
                        Text("Meals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        AnimatedContent(
                            targetState = list.count { it.status != Status.NOT_FOOD },
                            transitionSpec = {
                                (slideInVertically(tween(Motion.BASE, easing = Motion.BackOut)) { -it } + fadeIn())
                                    .togetherWith(slideOutVertically(tween(Motion.QUICK)) { it } + fadeOut())
                            },
                            label = "count",
                        ) { n ->
                            Text(
                                if (n == 1) "1 meal" else "$n meals",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (meals != null && list.isEmpty()) item { EmptyMeals(isToday, Modifier.enter(6)) }
                itemsIndexed(list, key = { _, m -> m.id }) { i, m ->
                    MealRow(m, Modifier.animateItem().enter(6 + i)) { onMeal(m.id) }
                }
            }
        }
        if (isToday) CaptureButtons(fabExpanded, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

private fun greeting(): String {
    val h = LocalTime.now().hour
    return when {
        h < 5 -> "Good night"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

/** Calorie ring that sweeps round while the number counts up, with macro bars filling one by one. */
@Composable
private fun HeroCard(date: String, done: List<Meal>, targets: Map<String, Double>, modifier: Modifier) {
    val sum = remember(done) { sumNutrients(done) }
    val kcal = sum["calories_kcal"] ?: 0.0
    val goal = targets["calories_kcal"] ?: 2000.0
    val on = MaterialTheme.colorScheme.onPrimaryContainer
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
                ProgressRing(
                    progress = (kcal / goal).toFloat(),
                    modifier = Modifier.fillMaxSize(),
                    stroke = 12.dp,
                    colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                    track = on.copy(alpha = 0.12f),
                    delayMs = 150,
                    memoryKey = "ring-$date",
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtInt(animateCount(kcal, delayMs = 150, memoryKey = "kcal-$date").toLong()), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = on)
                    Text("of ${fmt(goal)} kcal", style = MaterialTheme.typography.labelSmall, color = on.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                listOf("protein_g", "carbs_g", "fat_g", "fiber_g").forEachIndexed { i, key ->
                    NutrientBar(Nutrients.byKey.getValue(key), sum[key] ?: 0.0, targets[key] ?: 0.0, index = 3 + i, compact = true, memoryKey = "$key-$date")
                }
            }
        }
    }
}

@Composable
private fun SetupCard(onConnected: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val resume by UiRefresh.ticks.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val photoOk = remember(resume, tick) { Importer.hasPhotoAccess(ctx) }
    val notifyOk = remember(resume, tick) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val healthAvailable = remember { HealthSync.available(ctx) }
    var healthOk by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(resume, tick) { healthOk = HealthSync.connected(ctx) }

    val media = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        Work.scanNow(ctx)
    }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        tick++
        if (granted.any { it in HealthSync.READ }) onConnected()
        scope.launch(Dispatchers.IO) {
            HealthSync.refreshRecent(ctx, 14)
            HealthSync.backfillMeals(ctx)
        }
    }

    val needMedia = !photoOk || !notifyOk
    val needHealth = healthAvailable && healthOk == false
    // Collapses away (ease-in-out) once everything is allowed.
    AnimatedVisibility(
        visible = needMedia || needHealth,
        enter = fadeIn(tween(Motion.BASE)),
        exit = shrinkVertically(tween(Motion.BASE, easing = Motion.EaseInOut)) + fadeOut(tween(Motion.QUICK)),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.enter(1)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
                if (needMedia) SetupStep(
                    "Photos and notifications",
                    "Lets the app find new photos in your \"${Prefs(ctx).albumName}\" album, keep their location, " +
                        "and tell you when a meal has been analysed.",
                    "Allow",
                ) {
                    media.launch(arrayOf(
                        Manifest.permission.READ_MEDIA_IMAGES,
                        Manifest.permission.ACCESS_MEDIA_LOCATION,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ))
                }
                if (needHealth) SetupStep(
                    "Samsung Health",
                    "Shows your workouts, steps, heart rate and sleep from your watch, uses them to tailor suggestions, " +
                        "and adds each analysed meal to Samsung Health's food log.",
                    "Connect",
                ) { health.launch(HealthSync.permissionsToRequest(ctx)) }
            }
        }
    }
}

@Composable
private fun SetupStep(title: String, text: String, button: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onClick, interactionSource = interaction, modifier = Modifier.pressScale(interaction)) { Text(button) }
    }
}

/** Highlighted tip with a gently twinkling sparkle (ease-in-out, endless). */
@Composable
fun TipCard(title: String, text: String, modifier: Modifier = Modifier) {
    val twinkle by rememberInfiniteTransition(label = "twinkle")
        .animateFloat(0.85f, 1.1f, infiniteRepeatable(tween(1400, easing = Motion.EaseInOut), RepeatMode.Reverse), label = "t")
    val colors = MaterialTheme.colorScheme
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Box(Modifier.background(Brush.linearGradient(listOf(colors.tertiaryContainer, colors.secondaryContainer)))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    AppIcons.Sparkle, null, tint = colors.onTertiaryContainer,
                    modifier = Modifier.size(22.dp).graphicsLayer { scaleX = twinkle; scaleY = twinkle; rotationZ = (twinkle - 1f) * 40f },
                )
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onTertiaryContainer)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onTertiaryContainer)
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(date: String, a: ActivityDay?, connected: Boolean, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("From your watch", style = MaterialTheme.typography.titleMedium)
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when {
                a == null || a.isEmpty -> Text(
                    if (!connected) "Connect watch data to see workouts, steps, heart rate and sleep here."
                    else "Nothing from Samsung Health yet. If this stays empty, open Samsung Health › Settings › " +
                        "Health Connect and allow it to share your data.",
                    style = MaterialTheme.typography.bodyMedium, color = muted,
                )
                else -> {
                    Row(Modifier.fillMaxWidth()) {
                        CountStat("Steps", a.steps?.toDouble(), "", Modifier.weight(1f), memoryKey = "steps-$date")
                        // Samsung Health shares total (not active) calories, so fall back to that.
                        if (a.activeKcal != null) CountStat("Active", a.activeKcal, " kcal", Modifier.weight(1f), delayMs = 80, memoryKey = "active-$date")
                        else CountStat("Burned", a.totalKcal, " kcal", Modifier.weight(1f), delayMs = 80, memoryKey = "burned-$date")
                        CountStat("Avg HR", a.avgHr?.toDouble(), " bpm", Modifier.weight(1f), delayMs = 160, memoryKey = "hr-$date")
                        Stat("Sleep", a.sleepMin?.let { "${it / 60}h ${it % 60}m" } ?: "–", Modifier.weight(1f))
                    }
                    a.workouts.forEachIndexed { i, w -> WorkoutRow(w, Modifier.enter(i + 2, dx = 24.dp, dy = 0.dp)) }
                }
            }
        }
    }
}

@Composable
private fun WorkoutRow(w: Workout, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(w.title, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(Dates.time(w.start), "${w.minutes} min", w.details.firstOrNull()).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            listOfNotNull(w.kcal?.let { "${fmt(it)} kcal" }, w.avgHr?.let { "$it bpm" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Empty state with a camera that bobs gently (ease-in-out, endless). */
@Composable
private fun EmptyMeals(isToday: Boolean, modifier: Modifier) {
    val bob by rememberInfiniteTransition(label = "bob")
        .animateFloat(-4f, 4f, infiniteRepeatable(tween(1600, easing = Motion.EaseInOut), RepeatMode.Reverse), label = "y")
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isToday) Icon(
            AppIcons.Camera, null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp).graphicsLayer { translationY = bob },
        )
        if (isToday) Spacer(Modifier.width(12.dp))
        Text(
            if (isToday) "No meals yet. Tap \"Log meal\" or add a photo to your Meals album; it's analysed automatically."
            else "No meals logged on this day.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Meal card: presses in, shimmers while Gemini works, swaps in the result, and the score pops. */
@Composable
fun MealRow(m: Meal, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val working = m.status == Status.PENDING || m.status == Status.ANALYSING
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth().pressScale(interaction),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                if (m.thumbPath == null) Box(
                    Modifier.size(68.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { Icon(AppIcons.Sparkle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                else AsyncImage(
                    model = m.thumbPath?.let(::File),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.sharedPhoto("photo-${m.id}").size(68.dp).clip(RoundedCornerShape(14.dp)),
                )
                if (working) Box(Modifier.matchParentSize().clip(RoundedCornerShape(14.dp)).shimmer())
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = m,
                contentKey = { it.status },
                transitionSpec = {
                    (fadeIn(tween(Motion.BASE, easing = Motion.EaseInOut)) + slideInVertically(tween(Motion.SLOW, easing = Motion.ExpoOut)) { it / 3 })
                        .togetherWith(fadeOut(tween(Motion.QUICK)))
                },
                modifier = Modifier.weight(1f),
                label = "meal",
            ) { meal ->
                Column {
                    Text(meal.title(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (meal.status == Status.PENDING || meal.status == Status.ANALYSING) {
                            PulsingDot()
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            "${Dates.time(meal.takenAt)} · ${meal.subtitle()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (m.status == Status.DONE) m.healthScore?.let { ScoreRing(it) }
        }
    }
}

fun Meal.title(): String = when (status) {
    Status.DONE, Status.NOT_FOOD -> name ?: "Meal"
    Status.ANALYSING -> "Analysing…"
    Status.PENDING -> "Waiting to be analysed"
    Status.FAILED -> "Couldn't analyse yet"
    else -> name ?: "Meal"
}

fun Meal.subtitle(): String = when (status) {
    Status.DONE -> "${fmt(calories)} kcal · ${fmt(protein)} g protein"
    Status.NOT_FOOD -> "Not food, skipped"
    Status.FAILED -> if (attempts >= 6) "Gave up: ${error.orEmpty()}" else "Will retry: ${error.orEmpty()}"
    else -> "Usually takes under a minute"
}

/** "Log meal" pops in (back-out), shrinks to an icon while you scroll, and its gallery buddy follows. */
@Composable
private fun CaptureButtons(expanded: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        pending?.toUri()?.let { if (saved) MealCapture.complete(ctx, it) else MealCapture.discard(ctx, it) }
        pending = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) MealCapture.importPicked(ctx, uri)
    }
    val popBig = rememberReveal(Unit, delayMs = 250, durationMs = 550, easing = Motion.BackOut)
    val popSmall = rememberReveal(Unit, delayMs = 380, durationMs = 550, easing = Motion.BackOut)
    val bigPress = remember { MutableInteractionSource() }
    val smallPress = remember { MutableInteractionSource() }
    var showHistory by remember { mutableStateOf(false) }
    val popHistory = rememberReveal(Unit, delayMs = 500, durationMs = 550, easing = Motion.BackOut)
    val historyPress = remember { MutableInteractionSource() }
    if (showHistory) RepeatMealSheet(onDismiss = { showHistory = false }, onAdded = {})
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallFloatingActionButton(
            onClick = { showHistory = true },
            interactionSource = historyPress,
            modifier = Modifier.pressScale(historyPress, 0.9f).graphicsLayer { scaleX = popHistory; scaleY = popHistory },
        ) { Icon(AppIcons.History, contentDescription = "Add a meal from history") }
        SmallFloatingActionButton(
            onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            interactionSource = smallPress,
            modifier = Modifier.pressScale(smallPress, 0.9f).graphicsLayer { scaleX = popSmall; scaleY = popSmall },
        ) { Icon(AppIcons.Image, contentDescription = "Add a photo from the gallery") }
        ExtendedFloatingActionButton(
            onClick = {
                MealCapture.newPhotoUri(ctx)?.let { uri ->
                    pending = uri.toString()
                    camera.launch(uri)
                }
            },
            expanded = expanded,
            icon = { Icon(AppIcons.Camera, contentDescription = null) },
            text = { Text("Log meal") },
            interactionSource = bigPress,
            modifier = Modifier.pressScale(bigPress, 0.92f).graphicsLayer { scaleX = popBig; scaleY = popBig },
        )
    }
}
