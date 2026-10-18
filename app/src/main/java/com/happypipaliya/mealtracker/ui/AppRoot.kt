package com.happypipaliya.mealtracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.happypipaliya.mealtracker.Dates

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    NavTab("today", "Today", Icons.Filled.Home),
    NavTab("history", "History", AppIcons.History),
    NavTab("insights", "Insights", AppIcons.Chart),
    NavTab("settings", "Settings", Icons.Filled.Settings),
)

/** How deep a screen is: tabs 0, a day 1, a meal 2. Decides which way screens slide. */
private fun depth(route: String) = when {
    route.startsWith("meal/") -> 2
    route.startsWith("day/") || route == "energy" || route == "muscles" || route == "fruit" -> 1
    else -> 0
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppRoot(openMeal: MutableState<Long?>) {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf("today") }
    val route = stack.last()
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    val requested = openMeal.value
    LaunchedEffect(requested) {
        if (requested != null) {
            stack.add("meal/$requested")
            openMeal.value = null
        }
    }
    BackHandler(enabled = stack.size > 1, onBack = back)

    val isTab = tabs.any { it.route == route }
    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = isTab,
                enter = slideInVertically(tween(Motion.BASE, easing = Motion.ExpoOut)) { it } + fadeIn(tween(Motion.BASE)),
                exit = slideOutVertically(tween(Motion.QUICK, easing = Motion.EaseInOut)) { it } + fadeOut(tween(Motion.QUICK)),
            ) {
                NavigationBar {
                    tabs.forEach { t ->
                        val selected = route == t.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (!selected) { stack.clear(); stack.add(t.route) } },
                            icon = { BouncyIcon(t.icon, selected) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        SharedTransitionLayout(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    val from = depth(initialState)
                    val to = depth(targetState)
                    when {
                        // Going deeper: new screen slides in from the right (expo-out).
                        to > from -> (slideInHorizontally(tween(Motion.SLOW, easing = Motion.ExpoOut)) { it / 4 } + fadeIn(tween(Motion.BASE)))
                            .togetherWith(slideOutHorizontally(tween(Motion.SLOW, easing = Motion.ExpoOut)) { -it / 10 } + fadeOut(tween(Motion.QUICK)))
                        // Going back: the reverse.
                        to < from -> (slideInHorizontally(tween(Motion.SLOW, easing = Motion.ExpoOut)) { -it / 10 } + fadeIn(tween(Motion.BASE)))
                            .togetherWith(slideOutHorizontally(tween(Motion.SLOW, easing = Motion.ExpoOut)) { it / 4 } + fadeOut(tween(Motion.QUICK)))
                        // Switching tabs: cross-fade with a slight zoom (ease-in-out).
                        else -> (fadeIn(tween(Motion.BASE, easing = Motion.EaseInOut)) + scaleIn(tween(Motion.SLOW, easing = Motion.ExpoOut), initialScale = 0.97f))
                            .togetherWith(fadeOut(tween(Motion.QUICK, easing = Motion.EaseInOut)))
                    }.using(SizeTransform(clip = false))
                },
                label = "screens",
            ) { r ->
                CompositionLocalProvider(LocalSharedScope provides this@SharedTransitionLayout, LocalNavScope provides this) {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            r == "today" -> DayScreen(Dates.today(), isToday = true, onMeal = { stack.add("meal/$it") }, onBack = null, onEnergy = { stack.add("energy") }, onMuscles = { stack.add("muscles") }, onFruit = { stack.add("fruit") })
                            r == "energy" -> EnergyScreen(onBack = back, onMuscles = { stack.add("muscles") })
                            r == "muscles" -> MuscleScreen(onBack = back)
                            r == "fruit" -> FruitScreen(onBack = back)
                            r == "history" -> HistoryScreen(onDay = { stack.add("day/$it") })
                            r == "insights" -> InsightsScreen()
                            r == "settings" -> SettingsScreen()
                            r.startsWith("meal/") -> MealScreen(r.removePrefix("meal/").toLong(), onBack = back)
                            r.startsWith("day/") -> DayScreen(r.removePrefix("day/"), isToday = false, onMeal = { stack.add("meal/$it") }, onBack = back)
                        }
                    }
                }
            }
        }
    }
}

/** Tab icon that bounces (elastic) when it becomes selected. */
@Composable
private fun BouncyIcon(icon: ImageVector, selected: Boolean) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            scale.snapTo(0.6f)
            scale.animateTo(1f, tween(750, easing = Motion.Elastic))
        }
    }
    Icon(icon, contentDescription = null, modifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value })
}
