package com.happypipaliya.mealtracker.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import com.happypipaliya.mealtracker.Diagnostics
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.happypipaliya.mealtracker.BuildConfig
import com.happypipaliya.mealtracker.Gemini
import com.happypipaliya.mealtracker.HealthSync
import com.happypipaliya.mealtracker.Prefs
import com.happypipaliya.mealtracker.Targets
import com.happypipaliya.mealtracker.UiRefresh
import com.happypipaliya.mealtracker.Work
import com.happypipaliya.mealtracker.fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val p = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    val resume by UiRefresh.ticks.collectAsState()
    var profileTick by remember { mutableIntStateOf(0) }

    var album by remember { mutableStateOf(p.albumName) }
    var age by remember { mutableStateOf(p.age?.toString().orEmpty()) }
    var sex by remember { mutableStateOf(p.sex) }
    var height by remember { mutableStateOf(p.heightCm?.let { fmt(it) }.orEmpty()) }
    var weight by remember { mutableStateOf(p.weightKg?.let { fmt(it) }.orEmpty()) }
    var goal by remember { mutableStateOf(p.goal) }
    var plan by remember { mutableStateOf(p.trainingPlan) }
    var foodNotes by remember { mutableStateOf(p.foodNotes) }
    var writeHc by remember { mutableStateOf(p.writeNutritionToHealthConnect) }
    var canWrite by remember { mutableStateOf(false) }
    var notify by remember { mutableStateOf(p.notifications) }
    var digestRemind by remember { mutableStateOf(p.digestionReminders) }
    var connected by remember { mutableStateOf<Boolean?>(null) }
    var bodyMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(resume) {
        val granted = HealthSync.granted(ctx)
        connected = granted.any { it in HealthSync.READ }
        canWrite = HealthSync.WRITE_NUTRITION in granted
    }
    val targets = remember(profileTick) { Targets.forProfile(p) }

    val readLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        connected = granted.any { it in HealthSync.READ }
        canWrite = HealthSync.WRITE_NUTRITION in granted
        scope.launch(Dispatchers.IO) {
            HealthSync.refreshRecent(ctx, 14)
            HealthSync.backfillMeals(ctx)
        }
    }
    val writeLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        canWrite = HealthSync.WRITE_NUTRITION in granted
        writeHc = canWrite
        p.writeNutritionToHealthConnect = canWrite
        if (canWrite) scope.launch(Dispatchers.IO) { HealthSync.backfillMeals(ctx) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header("Settings", "Tap a section to open it")

        Section("Meal photos", 1, "Album: $album") {
            OutlinedTextField(
                value = album,
                onValueChange = { album = it; p.albumName = it },
                label = { Text("Gallery album to watch") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("New photos in this album are analysed automatically. \"Log meal\" saves straight into it (DCIM/${p.albumName}).")
            OutlinedButton(onClick = { Work.scanNow(ctx) }) { Text("Check the album now") }
        }

        Section("About you", 2, "Targets: ${fmt(targets["calories_kcal"])} kcal · ${fmt(targets["protein_g"])} g protein") {
            Hint("Optional. Helps Gemini tailor portions and suggestions. Sent with each meal photo.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Age", age, Modifier.weight(1f)) { age = it; p.age = it.toIntOrNull(); profileTick++ }
                NumberField("Height (cm)", height, Modifier.weight(1f)) { height = it; p.heightCm = it.toDoubleOrNull(); profileTick++ }
                NumberField("Weight (kg)", weight, Modifier.weight(1f)) { weight = it; p.weightKg = it.toDoubleOrNull(); profileTick++ }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("male" to "Male", "female" to "Female", "" to "Not set").forEach { (value, label) ->
                    FilterChip(selected = sex == value, onClick = { sex = value; p.sex = value; profileTick++ }, label = { Text(label) })
                }
            }
            TextButton(onClick = {
                scope.launch {
                    val (w, h) = withContext(Dispatchers.IO) { HealthSync.latestBody(ctx) }
                    w?.let { weight = fmt(it); p.weightKg = it }
                    h?.let { height = fmt(it); p.heightCm = it }
                    profileTick++
                    bodyMessage = if (w == null && h == null) "No weight or height found in Samsung Health." else "Filled in from Samsung Health."
                }
            }) { Text("Fill height and weight from Samsung Health") }
            bodyMessage?.let { Hint(it) }
            OutlinedTextField(goal, { goal = it; p.goal = it }, Modifier.fillMaxWidth(), label = { Text("Goal") })
            OutlinedTextField(plan, { plan = it; p.trainingPlan = it }, Modifier.fillMaxWidth(), label = { Text("Training plan") }, minLines = 2)
            OutlinedTextField(
                foodNotes, { foodNotes = it; p.foodNotes = it }, Modifier.fillMaxWidth(),
                label = { Text("Food notes (e.g. vegetarian, allergies)") }, minLines = 2,
            )
            Hint(
                "Daily targets: ${fmt(targets["calories_kcal"])} kcal, ${fmt(targets["protein_g"])} g protein, " +
                    "${fmt(targets["fiber_g"])} g fibre. Based on standard nutrition references and your profile.",
            )
        }

        Section("Samsung Health and watch", 3, if (connected == true) "Connected" + (if (writeHc && canWrite) " · meals go to the food log" else "") else "Not connected") {
            Hint(
                when (connected) {
                    true -> "Connected. Workouts, steps, heart rate and sleep are read through Health Connect."
                    false -> "Not connected yet."
                    null -> "Checking…"
                },
            )
            OutlinedButton(onClick = { readLauncher.launch(HealthSync.permissionsToRequest(ctx)) }) {
                Text(if (connected == true) "Review watch data access" else "Connect watch data")
            }
            ToggleRow("Add analysed meals to Samsung Health's food log", writeHc && canWrite) { on ->
                when {
                    !on -> { writeHc = false; p.writeNutritionToHealthConnect = false }
                    canWrite -> {
                        writeHc = true
                        p.writeNutritionToHealthConnect = true
                        scope.launch(Dispatchers.IO) { HealthSync.backfillMeals(ctx) }
                    }
                    else -> writeLauncher.launch(setOf(HealthSync.WRITE_NUTRITION))
                }
            }
            Hint(
                "Calories, protein, carbs, fat, fibre, sugar and vitamins and minerals go into Samsung Health under " +
                    "breakfast, lunch, dinner or snacks. Removing a meal here removes it there too. " +
                    "If you also log food by hand in Samsung Health, meals would be counted twice.",
            )
        }

        Section("Connections", 3, "Check that everything is syncing") { ConnectionsCheck() }

        Box(Modifier.enter(4)) { SmartAlarmSection() }

        Section("Notifications", 5, if (notify) "On" else "Off") {
            ToggleRow("Notify me when a meal is analysed", notify) { notify = it; p.notifications = it }
            ToggleRow("After-meal reminders (walk, when to have chai)", digestRemind) { digestRemind = it; p.digestionReminders = it }
        }

        Section("Background", 6, "Battery settings") {
            Hint("If meals only get analysed when you open the app, set Battery to \"Unrestricted\" for Meal Tracker.")
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text("Open app settings") }
        }

        Section("AI", 7, Gemini.MODELS.first()) {
            Hint(
                "Model: ${Gemini.MODELS.first()} (uses ${Gemini.MODELS.drop(1).joinToString(" or ")} when it's busy). " +
                    "Each meal photo, plus a short summary of your day and profile, is sent to Google's Gemini API. " +
                    "Location stays on this phone.",
            )
            Hint("Version ${BuildConfig.VERSION_NAME}")
        }
    }
}

@Composable
private fun Section(title: String, index: Int, subtitle: String? = null, content: @Composable () -> Unit) {
    CollapsibleCard(title, Modifier.enter(index), subtitle = subtitle, content = content)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ConnectionsCheck() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<Diagnostics.Check>?>(null) }
    var running by remember { mutableStateOf(false) }
    fun go() { scope.launch { running = true; checks = Diagnostics.run(ctx); running = false } }
    LaunchedEffect(Unit) { go() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        checks?.groupBy { it.group }?.forEach { (group, list) ->
            Text(group, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            list.forEachIndexed { i, c ->
                Row(Modifier.enter(i, dx = 16.dp, dy = 0.dp), verticalAlignment = Alignment.Top) {
                    val color = when (c.state) {
                        Diagnostics.State.OK -> energyColor(1.0)
                        Diagnostics.State.WARN -> energyColor(0.5)
                        Diagnostics.State.FAIL -> energyColor(0.05)
                    }
                    Text(when (c.state) { Diagnostics.State.OK -> "✓"; Diagnostics.State.WARN -> "!"; Diagnostics.State.FAIL -> "✕" },
                        color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp))
                    Column {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium)
                        Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        OutlinedButton(onClick = { go() }, enabled = !running) { Text(if (running) "Checking…" else "Check again") }
    }
}
