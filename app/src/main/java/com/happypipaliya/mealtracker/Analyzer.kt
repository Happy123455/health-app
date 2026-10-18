package com.happypipaliya.mealtracker

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Sends waiting meal photos to Gemini and stores the answers. */
object Analyzer {
    private val lock = Mutex()

    /** What the caller should do about meals that couldn't be analysed this time. */
    enum class Retry { NONE, SOON, AFTER_QUOTA_RESET }

    private enum class Outcome { OK, BUSY, QUOTA, FAILED }

    /** Imports new album photos and analyses everything waiting. */
    suspend fun runPending(ctx: Context): Retry = lock.withLock {
        withContext(Dispatchers.IO) {
            var retry = Retry.NONE
            val tried = mutableSetOf<Long>()
            repeat(3) { // a few rounds, in case new photos arrive while we work
                Importer.discoverAlbum(ctx)
                val batch = Db.get(ctx).toAnalyse(limit = 10).filter { it.id !in tried }
                if (batch.isEmpty()) return@withContext retry
                for (meal in batch) {
                    tried += meal.id
                    when (analyse(ctx, meal)) {
                        Outcome.BUSY -> retry = Retry.SOON
                        // Every model's daily limit is used up: further calls would fail too.
                        Outcome.QUOTA -> return@withContext Retry.AFTER_QUOTA_RESET
                        else -> Unit
                    }
                }
            }
            retry
        }
    }

    private suspend fun analyse(ctx: Context, meal: Meal): Outcome {
        val db = Db.get(ctx)
        val photo = meal.photoPath?.let(::File)?.takeIf { it.exists() }
        if (photo == null) {
            db.setStatus(meal.id, Status.FAILED, "The photo copy is missing", bumpAttempts = true)
            return Outcome.FAILED
        }
        db.setStatus(meal.id, Status.ANALYSING)
        return try {
            val reply = Gemini.generate(Gemini.mealRequest(photo.readBytes(), MealContext.build(ctx, meal)))
            val analysis = Analysis.parse(reply.json)
            db.saveAnalysis(meal.id, analysis, reply.text, reply.model)
            if (analysis.isFood) {
                Notify.mealAnalysed(ctx, meal.id, analysis)
                SleepLink.publishEnergy(ctx)
                HealthSync.writeMealIfEnabled(ctx, meal.id)
                db.meal(meal.id)?.let { DigestionEngine.scheduleReminder(ctx, it) }
            }
            Outcome.OK
        } catch (e: CancellationException) {
            db.setStatus(meal.id, Status.PENDING)
            throw e
        } catch (e: Gemini.ApiException) {
            // Hitting the daily limit isn't the meal's fault, so it doesn't count towards giving up.
            db.setStatus(meal.id, Status.FAILED, e.message, bumpAttempts = !e.quota)
            when {
                e.quota -> Outcome.QUOTA
                e.retryable -> Outcome.BUSY
                else -> Outcome.FAILED
            }
        } catch (e: Exception) {
            db.setStatus(meal.id, Status.FAILED, e.message ?: e.javaClass.simpleName, bumpAttempts = true)
            Outcome.FAILED
        }
    }
}

/** The text that goes to Gemini next to the photo: who the user is and how their day is going. */
object MealContext {
    fun build(ctx: Context, meal: Meal): String {
        val p = Prefs(ctx)
        val db = Db.get(ctx)
        val t = Targets.forProfile(p)
        return buildString {
            appendLine("Photo taken: ${Dates.long(meal.takenAt)} (local time).")
            appendLine("User profile: ${profile(p)}")
            appendLine("Daily targets: ${fmt(t["calories_kcal"])} kcal, protein ${fmt(t["protein_g"])} g, fibre ${fmt(t["fiber_g"])} g.")
            val earlier = db.mealsOn(meal.date).filter { it.status == Status.DONE && it.takenAt < meal.takenAt && it.id != meal.id }
            if (earlier.isEmpty()) {
                appendLine("Eaten earlier that day: nothing logged yet.")
            } else {
                appendLine("Eaten earlier that day:")
                earlier.forEach { appendLine("- ${Dates.time(it.takenAt)} ${it.name}: ${macros(it)}") }
                val s = sumNutrients(earlier)
                appendLine("Totals so far: ${fmt(s["calories_kcal"])} kcal, protein ${fmt(s["protein_g"])} g, carbs ${fmt(s["carbs_g"])} g, fat ${fmt(s["fat_g"])} g, fibre ${fmt(s["fiber_g"])} g.")
            }
            db.activity(meal.date)?.takeIf { !it.isEmpty }?.let { appendLine("Activity that day (from watch): ${it.describe()}") }
            meal.note?.takeIf { it.isNotBlank() }?.let { appendLine("User's note about this photo: \"$it\"") }
            appendLine(FruitEngine.seasonLine() + " When suggesting fruit, prefer these.")
        }
    }

    fun profile(p: Prefs): String = listOfNotNull(
        p.age?.let { "age $it" },
        p.sex.takeIf { it.isNotEmpty() },
        p.heightCm?.let { "${fmt(it)} cm" },
        p.weightKg?.let { "${fmt(it)} kg" },
        "goal: ${p.goal}",
        "training plan: ${p.trainingPlan}",
        p.foodNotes.takeIf { it.isNotBlank() }?.let { "food notes: $it" },
    ).joinToString("; ")

    fun macros(m: Meal) =
        "${fmt(m.calories)} kcal, protein ${fmt(m.protein)} g, carbs ${fmt(m.carbs)} g, fat ${fmt(m.fat)} g, fibre ${fmt(m.fiber)} g"
}

/** Looks across recent days for patterns and builds a plan. */
object InsightsAi {
    const val DAYS = 14

    suspend fun generate(ctx: Context): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            val db = Db.get(ctx)
            val p = Prefs(ctx)
            val targets = Targets.forProfile(p)
            val today = LocalDate.now()
            val from = today.minusDays(DAYS - 1L)
            val meals = db.doneMealsBetween(from.toString(), today.toString())
            require(meals.map { it.date }.distinct().size >= 2) { "Log meals on at least 2 days first." }
            val activity = db.activityBetween(from.toString(), today.toString()).associateBy { it.date }
            val data = buildString {
                appendLine("Today is ${Dates.long(System.currentTimeMillis())}.")
                appendLine("User profile: ${MealContext.profile(p)}")
                appendLine("Daily targets: " + Nutrients.all.filter { (targets[it.key] ?: 0.0) > 0 }
                    .joinToString(", ") { "${it.label} ${fmt(targets[it.key])} ${it.unit}" })
                for (i in 0 until DAYS) {
                    val d = from.plusDays(i.toLong()).toString()
                    val dayMeals = meals.filter { it.date == d }
                    val act = activity[d]
                    if (dayMeals.isEmpty() && (act == null || act.isEmpty)) continue
                    appendLine()
                    appendLine("${Dates.weekdayShort(d)} $d:")
                    if (dayMeals.isEmpty()) appendLine("- no meals logged")
                    dayMeals.forEach {
                        appendLine("- ${Dates.time(it.takenAt)} ${it.name} (${it.mealType}, score ${it.healthScore}/10): ${MealContext.macros(it)}")
                    }
                    if (dayMeals.isNotEmpty()) {
                        val s = sumNutrients(dayMeals)
                        val low = Nutrients.all.filter { it.kind == Kind.MICRO }.mapNotNull { n ->
                            val target = targets[n.key] ?: 0.0
                            val pct = if (target > 0) (s[n.key] ?: 0.0) / target * 100 else 100.0
                            if (pct < 50) "${n.label} ${pct.toInt()}%" else null
                        }
                        appendLine("  Day total: ${fmt(s["calories_kcal"])} kcal, protein ${fmt(s["protein_g"])} g, fibre ${fmt(s["fiber_g"])} g, sodium ${fmt(s["sodium_mg"])} mg, sugar ${fmt(s["sugar_g"])} g.")
                        if (low.isNotEmpty()) appendLine("  Below half of target: ${low.joinToString(", ")}")
                    }
                    if (act != null && !act.isEmpty) appendLine("  Activity: ${act.describe()}")
                }
            }
            val reply = Gemini.generate(Gemini.insightsRequest(data))
            db.saveInsight(reply.text)
        }
    }
}
