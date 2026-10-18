package com.happypipaliya.mealtracker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notify {
    const val CHANNEL = "analysis"

    fun mealAnalysed(ctx: Context, mealId: Long, a: Analysis) {
        if (!Prefs(ctx).notifications) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            ctx, mealId.toInt(),
            Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_MEAL, mealId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val summary = "Protein ${fmt(a.nutrients["protein_g"])} g · health score ${a.healthScore}/10"
        val tip = a.upgrades.firstOrNull()?.let { "Make it better: ${it.title}. ${it.how}" } ?: a.nextMeal
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meal)
            .setContentTitle("${a.mealName} · ${fmt(a.nutrients["calories_kcal"])} kcal")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n$tip"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(mealId.toInt(), notification)
    }
}
