package com.happypipaliya.mealtracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Scope for short fire-and-forget jobs that should outlive a screen (imports, deletes). */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

class MealApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(Notify.CHANNEL, "Meal analysis", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A short summary after each meal photo is analysed" }
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(DigestionEngine.CHANNEL, "Digestion reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "After a meal: a short walk, and when to have chai" }
        )
        Work.schedule(this)
    }
}

/** Bumped whenever the app comes to the foreground so screens re-check permissions and data. */
object UiRefresh {
    val ticks = MutableStateFlow(0)

    fun onResume(activity: ComponentActivity) {
        ticks.update { it + 1 }
        Work.scanNow(activity)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val today = LocalDate.now()
            // Last 5 days: the energy model needs a few nights for sleep debt.
            for (d in 0L..4L) runCatching { HealthSync.refreshDay(activity, today.minusDays(d)) }
            // Smart alarm: collect nights from the watch and label them while we can read sleep data.
            SleepLink.pullNights(activity)
            SleepLink.publishFavourites(activity)
            SleepLink.publishEnergy(activity)
            SleepLink.scheduleTraining(activity)
        }
    }
}
