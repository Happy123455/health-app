package com.happypipaliya.mealtracker

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDate

/**
 * Background jobs:
 * - a photo trigger that fires when a new image is saved anywhere on the phone,
 * - a scan that imports album photos and asks Gemini about them,
 * - a 15-minute safety net that also syncs watch data.
 */
object Work {
    private const val SCAN = "scan"
    private const val RETRY = "scan-retry"
    private const val TRIGGER = "photo-trigger"
    private const val PERIODIC = "periodic"

    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PeriodicWorker>(Duration.ofMinutes(15)).setConstraints(online).build(),
        )
        armTrigger(ctx, ExistingWorkPolicy.KEEP)
    }

    /** Runs a scan as soon as possible (after any scan that is already running). */
    fun scanNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            SCAN, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(online).build(),
        )
    }

    /** Gemini was busy or out of quota: scan again after [minutes]. */
    fun retryLater(ctx: Context, minutes: Long) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            RETRY, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RetryWorker>().setInitialDelay(Duration.ofMinutes(minutes)).build(),
        )
    }

    fun armTrigger(ctx: Context, policy: ExistingWorkPolicy) {
        val newPhoto = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
            .setTriggerContentMaxDelay(Duration.ofSeconds(30))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            TRIGGER, policy, OneTimeWorkRequestBuilder<TriggerWorker>().setConstraints(newPhoto).build(),
        )
    }
}

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { HealthSync.refreshDay(applicationContext, LocalDate.now()) }
        when (Analyzer.runPending(applicationContext)) {
            // Busy: wait longer after each failed try (1, 2, 5, 10, 20, 30 minutes).
            Analyzer.Retry.SOON -> Work.retryLater(
                applicationContext,
                listOf(1L, 2L, 5L, 10L, 20L, 30L)[Db.get(applicationContext).maxFailedAttempts().coerceIn(0, 5)],
            )
            Analyzer.Retry.AFTER_QUOTA_RESET -> Work.retryLater(applicationContext, 60)
            Analyzer.Retry.NONE -> Unit
        }
        return Result.success()
    }
}

class RetryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Work.scanNow(applicationContext)
        return Result.success()
    }
}

class TriggerWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Work.scanNow(applicationContext)
        Work.armTrigger(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

class PeriodicWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val today = LocalDate.now()
        runCatching {
            HealthSync.refreshDay(applicationContext, today)
            HealthSync.refreshDay(applicationContext, today.minusDays(1))
        }
        Work.armTrigger(applicationContext, ExistingWorkPolicy.KEEP)
        Analyzer.runPending(applicationContext)
        SleepLink.publishEnergy(applicationContext) // keep the watch battery current as fuel drains
        FruitEngine.fetchPrices(applicationContext) // at most every 6 h
        return Result.success()
    }
}
