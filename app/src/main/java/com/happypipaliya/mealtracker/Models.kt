package com.happypipaliya.mealtracker

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Status {
    const val PENDING = "PENDING"
    const val ANALYSING = "ANALYSING"
    const val DONE = "DONE"
    const val NOT_FOOD = "NOT_FOOD"
    const val FAILED = "FAILED"
    const val DELETED = "DELETED"
}

data class Meal(
    val id: Long,
    val mediaId: Long?,
    val takenAt: Long,
    val date: String,
    val lat: Double?,
    val lon: Double?,
    val photoPath: String?,
    val thumbPath: String?,
    val status: String,
    val error: String?,
    val attempts: Int,
    val note: String?,
    val name: String?,
    val mealType: String?,
    val calories: Double?,
    val protein: Double?,
    val carbs: Double?,
    val fat: Double?,
    val fiber: Double?,
    val healthScore: Int?,
    val analysisJson: String?,
    val model: String?,
    val analysedAt: Long?,
) {
    val analysis: Analysis? by lazy {
        analysisJson?.let { runCatching { Analysis.parse(JSONObject(it)) }.getOrNull() }
    }
}

data class Item(
    val name: String,
    val portion: String,
    val grams: Double,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
)

data class Upgrade(val title: String, val how: String, val why: String)

/** Gemini's answer for one photo (see the schema in [Gemini]). */
data class Analysis(
    val isFood: Boolean,
    val mealName: String,
    val mealType: String,
    val items: List<Item>,
    val nutrients: Map<String, Double>,
    val confidence: String,
    val assumptions: String,
    val healthScore: Int,
    val highlights: List<String>,
    val watchOuts: List<String>,
    val upgrades: List<Upgrade>,
    val nextMeal: String,
) {
    companion object {
        fun parse(o: JSONObject): Analysis {
            val n = o.optJSONObject("nutrients") ?: JSONObject()
            return Analysis(
                isFood = o.optBoolean("is_food", true),
                mealName = o.optString("meal_name").ifBlank { "Meal" },
                mealType = o.optString("meal_type"),
                items = o.optJSONArray("items").objects().map {
                    Item(
                        it.optString("name"), it.optString("portion"), it.num("grams"),
                        it.num("calories_kcal"), it.num("protein_g"), it.num("carbs_g"), it.num("fat_g"),
                    )
                },
                nutrients = Nutrients.all.associate { it.key to n.num(it.key) },
                confidence = o.optString("confidence"),
                assumptions = o.optString("assumptions"),
                healthScore = o.optInt("health_score", 0),
                highlights = o.optJSONArray("highlights").strings(),
                watchOuts = o.optJSONArray("watch_outs").strings(),
                upgrades = o.optJSONArray("upgrades").objects().map {
                    Upgrade(it.optString("title"), it.optString("how"), it.optString("why"))
                },
                nextMeal = o.optString("next_meal"),
            )
        }
    }
}

data class Workout(
    val title: String,
    val start: Long,
    val end: Long,
    val kcal: Double?,
    val avgHr: Long?,
    val source: String,
    val details: List<String>,
) {
    val minutes: Long get() = (end - start) / 60_000

    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("start", start).put("end", end)
        .put("kcal", kcal ?: JSONObject.NULL).put("avgHr", avgHr ?: JSONObject.NULL)
        .put("source", source).put("details", JSONArray(details))

    companion object {
        fun fromJson(o: JSONObject) = Workout(
            title = o.optString("title"),
            start = o.optLong("start"),
            end = o.optLong("end"),
            kcal = if (o.isNull("kcal")) null else o.optDouble("kcal"),
            avgHr = if (o.isNull("avgHr")) null else o.optLong("avgHr"),
            source = o.optString("source"),
            details = o.optJSONArray("details").strings(),
        )
    }
}

/** One day of watch data, copied from Health Connect into our own database. */
data class ActivityDay(
    val date: String,
    val steps: Long?,
    val distanceM: Double?,
    val activeKcal: Double?,
    val totalKcal: Double?,
    val exerciseMin: Long?,
    val avgHr: Long?,
    val minHr: Long?,
    val maxHr: Long?,
    val restingHr: Long?,
    val sleepMin: Long?,
    val workouts: List<Workout>,
    val updatedAt: Long,
) {
    val isEmpty: Boolean get() = steps == null && workouts.isEmpty() && avgHr == null && sleepMin == null

    /** One line for prompts, e.g. "5,420 steps; 310 active kcal; workouts: ...". */
    fun describe(): String = buildList {
        steps?.let { add("${fmtInt(it)} steps") }
        activeKcal?.let { add("${fmt(it)} active kcal") } ?: totalKcal?.let { add("${fmt(it)} kcal burned in total") }
        if (workouts.isNotEmpty()) add("workouts: " + workouts.joinToString(", ") { w ->
            w.title + " (" + listOfNotNull("${w.minutes} min", w.kcal?.let { "${fmt(it)} kcal" }).joinToString(", ") + ")"
        })
        restingHr?.let { add("resting heart rate $it bpm") }
        sleepMin?.let { add("slept ${fmtMinutes(it)}") }
    }.joinToString("; ").ifEmpty { "no data" }
}

data class DaySummary(
    val date: String,
    val meals: Int,
    val kcal: Double,
    val protein: Double,
    val fiber: Double,
    val thumbs: List<String>,
)

data class Insight(val createdAt: Long, val json: JSONObject)

/** A smart-alarm night as stored on the phone. */
data class NightRow(
    val record: com.happypipaliya.sleepcore.NightRecord,
    val labels: List<com.happypipaliya.sleepcore.Stage>,
    val wakeStage: com.happypipaliya.sleepcore.Stage?,
    val labelled: Boolean,
    val accuracy: Double?,
)

fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

private fun JSONObject.num(key: String): Double = optDouble(key, 0.0).let { if (it.isNaN()) 0.0 else it }

object Dates {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun dateOf(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    fun today(): String = LocalDate.now(zone).toString()

    fun label(date: String): String {
        val d = LocalDate.parse(date)
        val today = LocalDate.now(zone)
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
    }

    fun full(date: String): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy"))

    fun time(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

    fun long(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"))

    fun weekdayShort(date: String): String = LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE"))
}
