package com.happypipaliya.mealtracker

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import com.happypipaliya.sleepcore.SleepSession
import com.happypipaliya.sleepcore.Stage
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

private const val TAG = "HealthSync"

/**
 * Reads watch data (shared by Samsung Health through Health Connect) into the local database,
 * and optionally writes analysed meals back so they show up in Samsung Health.
 */
object HealthSync {
    val READ: Set<String> = setOf(
        StepsRecord::class, ExerciseSessionRecord::class, ActiveCaloriesBurnedRecord::class,
        TotalCaloriesBurnedRecord::class, DistanceRecord::class, HeartRateRecord::class,
        RestingHeartRateRecord::class, SleepSessionRecord::class, WeightRecord::class, HeightRecord::class,
    ).map { HealthPermission.getReadPermission(it) }.toSet()

    val WRITE_NUTRITION: String = HealthPermission.getWritePermission(NutritionRecord::class)

    /** Water logged in Samsung Health (its water tracker shares to Health Connect). */
    val READ_WATER: String = HealthPermission.getReadPermission(HydrationRecord::class)

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    private fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx)

    /** Read access, writing meals (if turned on), plus background and history access when supported. */
    fun permissionsToRequest(ctx: Context): Set<String> {
        val features = client(ctx).features
        fun supported(feature: Int) = features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        return buildSet {
            addAll(READ)
            add(READ_WATER)
            if (Prefs(ctx).writeNutritionToHealthConnect) add(WRITE_NUTRITION)
            if (supported(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            if (supported(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }
    }

    suspend fun granted(ctx: Context): Set<String> =
        if (!available(ctx)) emptySet()
        else runCatching { client(ctx).permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    suspend fun connected(ctx: Context) = granted(ctx).any { it in READ }

    /** Copies one day of watch data into the database. Returns null if there's no access. */
    suspend fun refreshDay(ctx: Context, date: LocalDate): ActivityDay? {
        if (!available(ctx)) return null
        val granted = granted(ctx)
        if (granted.none { it in READ }) return null
        fun has(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in granted

        val c = client(ctx)
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val day = TimeRangeFilter.between(start, end)

        val metrics = buildSet<AggregateMetric<*>> {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (has(HeartRateRecord::class)) addAll(listOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX))
            if (has(RestingHeartRateRecord::class)) add(RestingHeartRateRecord.BPM_AVG)
            if (has(ExerciseSessionRecord::class)) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        }
        val agg = if (metrics.isEmpty()) null else c.aggregate(AggregateRequest(metrics, day))

        // Sleep that ended on this day (i.e. last night).
        val sleepRecords = if (!has(SleepSessionRecord::class)) emptyList() else
            c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start.minus(Duration.ofHours(18)), end))).records
        // Keep every session (nights and naps) for the energy model's sleep debt and nap handling.
        Db.get(ctx).saveSleeps(sleepRecords.map { SleepSession(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) })
        val sleepMin = sleepRecords.filter { !it.endTime.isBefore(start) && it.endTime.isBefore(end) }
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
            .takeIf { it > 0 }

        val workouts = if (!has(ExerciseSessionRecord::class)) emptyList() else
            c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, day)).records.map { s ->
                workout(c, s, has(ActiveCaloriesBurnedRecord::class), has(TotalCaloriesBurnedRecord::class), has(HeartRateRecord::class))
            }

        val result = ActivityDay(
            date = date.toString(),
            steps = agg?.get(StepsRecord.COUNT_TOTAL),
            distanceM = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            activeKcal = agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            totalKcal = agg?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            exerciseMin = agg?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)?.toMinutes(),
            avgHr = agg?.get(HeartRateRecord.BPM_AVG),
            minHr = agg?.get(HeartRateRecord.BPM_MIN),
            maxHr = agg?.get(HeartRateRecord.BPM_MAX),
            restingHr = agg?.get(RestingHeartRateRecord.BPM_AVG),
            sleepMin = sleepMin,
            workouts = workouts,
            updatedAt = System.currentTimeMillis(),
        )
        Db.get(ctx).saveActivity(result)
        return result
    }

    /** Fills in recent history, e.g. right after access is granted. */
    suspend fun refreshRecent(ctx: Context, days: Int) {
        val today = LocalDate.now()
        for (i in 0 until days) runCatching { refreshDay(ctx, today.minusDays(i.toLong())) }
            .onFailure { Log.w(TAG, "refresh failed", it) }
    }

    private suspend fun workout(
        c: HealthConnectClient, s: ExerciseSessionRecord, active: Boolean, total: Boolean, hr: Boolean,
    ): Workout {
        val metrics = buildSet<AggregateMetric<*>> {
            if (active) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (total) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (hr) add(HeartRateRecord.BPM_AVG)
        }
        val a = if (metrics.isEmpty()) null
        else runCatching { c.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(s.startTime, s.endTime))) }.getOrNull()
        val typeName = ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[s.exerciseType]
            ?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Workout"
        val sets = s.segments.filter { it.repetitions > 0 }
        val details = if (sets.isEmpty()) emptyList()
        else listOf("${sets.size} sets, ${sets.sumOf { it.repetitions }} reps")
        return Workout(
            title = s.title?.takeIf { it.isNotBlank() } ?: typeName,
            start = s.startTime.toEpochMilli(),
            end = s.endTime.toEpochMilli(),
            kcal = a?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories
                ?: a?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            avgHr = a?.get(HeartRateRecord.BPM_AVG),
            source = s.metadata.dataOrigin.packageName,
            details = details,
        )
    }

    /**
     * Samsung's sleep stages between two moments, as (start, end, stage) intervals.
     * Returns null when sleep data can't be read right now (no access, or app in the background).
     */
    suspend fun sleepStages(ctx: Context, from: Long, to: Long): List<Triple<Long, Long, Stage>>? {
        if (!available(ctx)) return null
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted(ctx)) return null
        return runCatching {
            client(ctx).readRecords(
                ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))),
            ).records.flatMap { session ->
                session.stages.map { Triple(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), stageOf(it.stage)) }
            }
        }.getOrNull()
    }

    private fun stageOf(type: Int) = when (type) {
        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> Stage.AWAKE
        SleepSessionRecord.STAGE_TYPE_LIGHT -> Stage.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> Stage.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> Stage.REM
        else -> Stage.UNKNOWN
    }

    /** Drinks between two moments as (time, ml); null when water isn't shared with the app. */
    suspend fun water(ctx: Context, from: Long, to: Long): List<Pair<Long, Double>>? {
        if (!available(ctx) || READ_WATER !in granted(ctx)) return null
        return runCatching {
            client(ctx).readRecords(
                ReadRecordsRequest(HydrationRecord::class, TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(to))),
            ).records.map { it.startTime.toEpochMilli() to it.volume.inMilliliters }
        }.getOrNull()
    }

    /** Latest weight (kg) and height (cm) from Health Connect, for pre-filling the profile. */
    suspend fun latestBody(ctx: Context): Pair<Double?, Double?> {
        if (!available(ctx)) return null to null
        val granted = granted(ctx)
        val c = client(ctx)
        val range = TimeRangeFilter.between(Instant.now().minus(Duration.ofDays(3650)), Instant.now())
        val weight = if (HealthPermission.getReadPermission(WeightRecord::class) !in granted) null else
            runCatching { c.readRecords(ReadRecordsRequest(WeightRecord::class, range, ascendingOrder = false, pageSize = 1)).records.firstOrNull()?.weight?.inKilograms }.getOrNull()
        val height = if (HealthPermission.getReadPermission(HeightRecord::class) !in granted) null else
            runCatching { c.readRecords(ReadRecordsRequest(HeightRecord::class, range, ascendingOrder = false, pageSize = 1)).records.firstOrNull()?.height?.inMeters?.times(100) }.getOrNull()
        return weight to height
    }

    /** Saves the meal as a Health Connect nutrition entry (shows up in Samsung Health), if turned on. */
    suspend fun writeMealIfEnabled(ctx: Context, mealId: Long) {
        if (!Prefs(ctx).writeNutritionToHealthConnect || !available(ctx)) return
        if (WRITE_NUTRITION !in granted(ctx)) return
        val meal = Db.get(ctx).meal(mealId) ?: return
        val a = meal.analysis ?: return
        val p = Prefs(ctx)
        runCatching { client(ctx).insertRecords(listOf(nutritionRecord(meal, a))) }
            .onSuccess { p.hcWriteAt = System.currentTimeMillis(); p.hcWriteError = "" }
            .onFailure { Log.w(TAG, "Could not write meal $mealId", it); p.hcWriteAt = System.currentTimeMillis(); p.hcWriteError = it.message ?: it.javaClass.simpleName }
    }

    /**
     * Sends every analysed meal to Samsung Health, e.g. right after access is granted. Safe to repeat:
     * each meal has a fixed id, so it updates the existing entry instead of adding a duplicate.
     */
    suspend fun backfillMeals(ctx: Context) {
        if (!Prefs(ctx).writeNutritionToHealthConnect || !available(ctx)) return
        if (WRITE_NUTRITION !in granted(ctx)) return
        Db.get(ctx).allDoneMeals().mapNotNull { m -> m.analysis?.let { nutritionRecord(m, it) } }
            .chunked(100)
            .forEach { batch ->
                runCatching { client(ctx).insertRecords(batch) }.onFailure { Log.w(TAG, "Backfill failed", it) }
            }
    }

    suspend fun deleteMeal(ctx: Context, mealId: Long) {
        if (!available(ctx) || WRITE_NUTRITION !in granted(ctx)) return
        runCatching { client(ctx).deleteRecords(NutritionRecord::class, emptyList(), listOf("meal-$mealId")) }
    }

    private fun nutritionRecord(meal: Meal, a: Analysis): NutritionRecord {
        val n = a.nutrients
        fun g(k: String) = n[k]?.takeIf { it > 0 }?.let { Mass.grams(it) }
        fun mg(k: String) = n[k]?.takeIf { it > 0 }?.let { Mass.milligrams(it) }
        fun mcg(k: String) = n[k]?.takeIf { it > 0 }?.let { Mass.micrograms(it) }
        val start = Instant.ofEpochMilli(meal.takenAt)
        val offset = ZoneId.systemDefault().rules.getOffset(start)
        return NutritionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = start.plus(Duration.ofMinutes(15)),
            endZoneOffset = offset,
            metadata = Metadata.manualEntry("meal-${meal.id}", meal.analysedAt ?: System.currentTimeMillis()),
            name = a.mealName,
            mealType = when (a.mealType) {
                "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
                "lunch" -> MealType.MEAL_TYPE_LUNCH
                "dinner" -> MealType.MEAL_TYPE_DINNER
                "snack", "drink" -> MealType.MEAL_TYPE_SNACK
                else -> MealType.MEAL_TYPE_UNKNOWN
            },
            energy = n["calories_kcal"]?.takeIf { it > 0 }?.let { Energy.kilocalories(it) },
            protein = g("protein_g"),
            totalCarbohydrate = g("carbs_g"),
            totalFat = g("fat_g"),
            dietaryFiber = g("fiber_g"),
            sugar = g("sugar_g"),
            saturatedFat = g("saturated_fat_g"),
            cholesterol = mg("cholesterol_mg"),
            sodium = mg("sodium_mg"),
            potassium = mg("potassium_mg"),
            calcium = mg("calcium_mg"),
            iron = mg("iron_mg"),
            magnesium = mg("magnesium_mg"),
            zinc = mg("zinc_mg"),
            phosphorus = mg("phosphorus_mg"),
            selenium = mcg("selenium_mcg"),
            vitaminA = mcg("vitamin_a_mcg"),
            vitaminC = mg("vitamin_c_mg"),
            vitaminD = mcg("vitamin_d_mcg"),
            vitaminE = mg("vitamin_e_mg"),
            vitaminK = mcg("vitamin_k_mcg"),
            thiamin = mg("thiamin_mg"),
            riboflavin = mg("riboflavin_mg"),
            niacin = mg("niacin_mg"),
            vitaminB6 = mg("vitamin_b6_mg"),
            vitaminB12 = mcg("vitamin_b12_mcg"),
            folate = mcg("folate_mcg"),
        )
    }
}
