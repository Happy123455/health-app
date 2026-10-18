package com.happypipaliya.mealtracker

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.happypipaliya.sleepcore.CheckIn
import com.happypipaliya.sleepcore.Codec
import com.happypipaliya.sleepcore.LoggedActivity
import com.happypipaliya.sleepcore.SleepSession
import com.happypipaliya.sleepcore.LabelledNight
import com.happypipaliya.sleepcore.NightRecord
import com.happypipaliya.sleepcore.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Everything the app remembers: meals with Gemini's full answers, daily watch data, and insights. */
class Db private constructor(context: Context) : SQLiteOpenHelper(context, "meals.db", null, 6) {

    override fun onCreate(db: SQLiteDatabase) {
        createNights(db)
        createCheckIns(db)
        createSleepAndActivities(db)
        createPrices(db)
        createWater(db)
        db.execSQL(
            """CREATE TABLE meals(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_id INTEGER, taken_at INTEGER NOT NULL, date TEXT NOT NULL,
                lat REAL, lon REAL, photo_path TEXT, thumb_path TEXT,
                status TEXT NOT NULL, error TEXT, attempts INTEGER NOT NULL DEFAULT 0, note TEXT,
                name TEXT, meal_type TEXT, calories REAL, protein REAL, carbs REAL, fat REAL, fiber REAL,
                health_score INTEGER, analysis_json TEXT, model TEXT, analysed_at INTEGER,
                updated_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE UNIQUE INDEX meals_media ON meals(media_id) WHERE media_id IS NOT NULL")
        db.execSQL("CREATE INDEX meals_date ON meals(date)")
        db.execSQL(
            """CREATE TABLE activity_days(
                date TEXT PRIMARY KEY, steps INTEGER, distance_m REAL, active_kcal REAL, total_kcal REAL,
                exercise_min INTEGER, avg_hr INTEGER, min_hr INTEGER, max_hr INTEGER, resting_hr INTEGER,
                sleep_min INTEGER, workouts_json TEXT, updated_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE TABLE insights(id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, json TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createNights(db)
        if (oldVersion < 3) createCheckIns(db)
        if (oldVersion < 4) createSleepAndActivities(db)
        if (oldVersion < 5) createPrices(db)
        if (oldVersion < 6) createWater(db)
    }

    /** Water logged in Meal Tracker (phone or watch). Samsung Health doesn't share its water log with Health Connect. */
    private fun createWater(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS water(time INTEGER PRIMARY KEY, ml REAL NOT NULL)")

    fun logWater(time: Long, ml: Double) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO water(time, ml) VALUES(?, ?)", arrayOf(time, ml))
        changed()
    }

    fun deleteWater(time: Long) {
        writableDatabase.execSQL("DELETE FROM water WHERE time=?", arrayOf(time))
        changed()
    }

    fun waterBetween(from: Long, to: Long): List<Pair<Long, Double>> =
        readableDatabase.rawQuery("SELECT time, ml FROM water WHERE time>=? AND time<=? ORDER BY time", arrayOf(from.toString(), to.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getDouble(1)) } }

    /** Daily mandi prices (rupees per quintal, as Agmarknet reports them), kept to build a price history. */
    private fun createPrices(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE IF NOT EXISTS prices(
            date TEXT NOT NULL, district TEXT NOT NULL, market TEXT NOT NULL, commodity TEXT NOT NULL, variety TEXT NOT NULL,
            min_price REAL, modal_price REAL, max_price REAL, PRIMARY KEY(date, market, commodity, variety))"""
    )

    data class PriceRow(val date: String, val district: String, val market: String, val commodity: String, val variety: String, val min: Double, val modal: Double, val max: Double)

    fun savePrices(rows: List<PriceRow>) {
        if (rows.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            rows.forEach {
                writableDatabase.execSQL(
                    "INSERT OR REPLACE INTO prices(date, district, market, commodity, variety, min_price, modal_price, max_price) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf(it.date, it.district, it.market, it.commodity, it.variety, it.min, it.modal, it.max),
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        changed()
    }

    /** For the connections check: newest value of a column, or null. */
    fun newest(table: String, column: String, where: String = "1"): Long? =
        readableDatabase.rawQuery("SELECT MAX($column) FROM $table WHERE $where", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    fun count(table: String, where: String = "1"): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun pricesSince(date: String): List<PriceRow> =
        readableDatabase.rawQuery("SELECT date, district, market, commodity, variety, min_price, modal_price, max_price FROM prices WHERE date>=? ORDER BY date", arrayOf(date))
            .use { c -> buildList { while (c.moveToNext()) add(PriceRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getDouble(5), c.getDouble(6), c.getDouble(7))) } }

    /** One row per wake-up recorded by the watch; labels are Samsung's stage for each 30-second epoch. */
    private fun createNights(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE IF NOT EXISTS nights(
            monitor_start INTEGER PRIMARY KEY, record TEXT NOT NULL, labels TEXT, wake_stage TEXT,
            labelled INTEGER NOT NULL DEFAULT 0, accuracy REAL, received_at INTEGER NOT NULL)"""
    )

    /** Sleep sessions (nights and naps) copied from Health Connect, and activities logged by hand. */
    private fun createSleepAndActivities(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sleep_sessions(start INTEGER PRIMARY KEY, end_time INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS activities(start INTEGER PRIMARY KEY, type TEXT NOT NULL, minutes INTEGER NOT NULL)")
    }

    fun saveSleeps(sessions: List<SleepSession>) {
        if (sessions.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            sessions.forEach {
                writableDatabase.execSQL("INSERT OR REPLACE INTO sleep_sessions(start, end_time) VALUES(?, ?)", arrayOf(it.start, it.end))
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        changed()
    }

    fun sleepsSince(t: Long): List<SleepSession> =
        readableDatabase.rawQuery("SELECT start, end_time FROM sleep_sessions WHERE end_time>=? ORDER BY start", arrayOf(t.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(SleepSession(c.getLong(0), c.getLong(1))) } }

    fun logActivity(a: LoggedActivity) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO activities(start, type, minutes) VALUES(?, ?, ?)", arrayOf(a.start, a.type, a.minutes))
        changed()
    }

    fun deleteActivity(start: Long) {
        writableDatabase.execSQL("DELETE FROM activities WHERE start=?", arrayOf(start))
        changed()
    }

    fun activitiesSince(t: Long): List<LoggedActivity> =
        readableDatabase.rawQuery("SELECT type, start, minutes FROM activities WHERE start>=? ORDER BY start", arrayOf(t.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(LoggedActivity(c.getString(0), c.getLong(1), c.getInt(2))) } }

    /** "How energetic do you feel?" answers, with the model's inputs at that moment (for learning). */
    private fun createCheckIns(db: SQLiteDatabase) = db.execSQL(
        "CREATE TABLE IF NOT EXISTS check_ins(time INTEGER PRIMARY KEY, value INTEGER NOT NULL, features TEXT NOT NULL, predicted REAL)"
    )

    fun saveCheckIn(c: CheckIn, predicted: Double) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO check_ins(time, value, features, predicted) VALUES(?, ?, ?, ?)",
            arrayOf(c.time, c.value, c.features.joinToString(","), predicted),
        )
        changed()
    }

    fun checkIns(): List<CheckIn> =
        readableDatabase.rawQuery("SELECT time, value, features FROM check_ins ORDER BY time", null).use { c ->
            buildList {
                while (c.moveToNext()) add(CheckIn(c.getLong(0), c.getInt(1), c.getString(2).split(',').map { it.toDouble() }))
            }
        }

    // ---- smart alarm nights ----

    /** Saves a night from the watch; a resend (e.g. with grogginess added) updates it but keeps its labels. */
    fun saveNight(r: NightRecord) {
        writableDatabase.execSQL(
            "INSERT INTO nights(monitor_start, record, received_at) VALUES(?, ?, ?) " +
                "ON CONFLICT(monitor_start) DO UPDATE SET record=excluded.record",
            arrayOf(r.monitorStart, Codec.encode(r), System.currentTimeMillis()),
        )
        changed()
    }

    fun nightsToLabel(): List<NightRecord> =
        readableDatabase.rawQuery("SELECT record FROM nights WHERE labelled=0 ORDER BY monitor_start", null)
            .use { c -> buildList { while (c.moveToNext()) Codec.decodeNight(c.getString(0))?.let(::add) } }

    fun saveLabels(monitorStart: Long, labels: List<Stage>, wakeStage: Stage?) {
        writableDatabase.execSQL(
            "UPDATE nights SET labels=?, wake_stage=?, labelled=1 WHERE monitor_start=?",
            arrayOf(Codec.encodeStages(labels), wakeStage?.name, monitorStart),
        )
        changed()
    }

    fun setNightAccuracy(monitorStart: Long, accuracy: Double?) {
        writableDatabase.execSQL("UPDATE nights SET accuracy=? WHERE monitor_start=?", arrayOf(accuracy, monitorStart))
        changed()
    }

    /** Every night, newest first, with Samsung's labels when they have arrived. */
    fun nights(limit: Int = 60): List<NightRow> = readableDatabase.rawQuery(
        "SELECT record, labels, wake_stage, labelled, accuracy FROM nights ORDER BY monitor_start DESC LIMIT $limit", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val record = Codec.decodeNight(c.getString(0)) ?: continue
                add(
                    NightRow(
                        record = record,
                        labels = Codec.decodeStages(c.getString(1)),
                        wakeStage = c.getString(2)?.let { runCatching { Stage.valueOf(it) }.getOrNull() },
                        labelled = c.getInt(3) == 1,
                        accuracy = if (c.isNull(4)) null else c.getDouble(4),
                    )
                )
            }
        }
    }

    fun labelledNights(): List<LabelledNight> =
        nights(limit = 1000).filter { it.labelled && it.labels.isNotEmpty() }
            .map { LabelledNight(it.record, it.labels, it.wakeStage) }
            .sortedBy { it.record.monitorStart }

    // ---- meals ----

    /** Returns the new row id, or -1 if this gallery photo was already imported. */
    fun insertMeal(
        mediaId: Long?, takenAt: Long, date: String, lat: Double?, lon: Double?, photo: String, thumb: String,
    ): Long {
        val v = ContentValues().apply {
            put("media_id", mediaId)
            put("taken_at", takenAt)
            put("date", date)
            put("lat", lat)
            put("lon", lon)
            put("photo_path", photo)
            put("thumb_path", thumb)
            put("status", Status.PENDING)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("meals", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            .also { if (it > 0) changed() }
    }

    /** A meal added without a photo (already "analysed" from an estimate). */
    fun insertQuickMeal(
        takenAt: Long, a: Analysis, json: String, model: String = "estimate", photo: String? = null, thumb: String? = null,
    ): Long {
        val v = ContentValues().apply {
            put("taken_at", takenAt)
            put("date", Dates.dateOf(takenAt))
            put("photo_path", photo)
            put("thumb_path", thumb)
            put("status", Status.PENDING)
            put("updated_at", System.currentTimeMillis())
        }
        val id = writableDatabase.insert("meals", null, v)
        if (id > 0) saveAnalysis(id, a, json, model)
        return id
    }

    /** Meals you've had in the last 60 days, one per dish, most often eaten first (photo meals only). */
    fun favourites(limit: Int = 20): List<Pair<Meal, Int>> {
        val from = java.time.LocalDate.now().minusDays(60).toString()
        return meals("SELECT * FROM meals WHERE status='DONE' AND IFNULL(model,'')!='estimate' AND date>=? ORDER BY taken_at DESC", from)
            .groupBy { it.name.orEmpty().trim().lowercase() }
            .map { (_, list) -> list.first() to list.size }
            .sortedWith(compareByDescending<Pair<Meal, Int>> { it.second }.thenByDescending { it.first.takenAt })
            .take(limit)
    }

    fun knownMediaIds(): Set<Long> =
        readableDatabase.rawQuery("SELECT media_id FROM meals WHERE media_id IS NOT NULL", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    fun meal(id: Long): Meal? = meals("SELECT * FROM meals WHERE id=?", id.toString()).firstOrNull()

    fun mealsOn(date: String): List<Meal> =
        meals("SELECT * FROM meals WHERE date=? AND status!='DELETED' ORDER BY taken_at", date)

    fun allDoneMeals(): List<Meal> = meals("SELECT * FROM meals WHERE status='DONE' ORDER BY taken_at")

    fun doneMealsBetween(from: String, to: String): List<Meal> =
        meals("SELECT * FROM meals WHERE date>=? AND date<=? AND status='DONE' ORDER BY taken_at", from, to)

    /** Meals waiting for Gemini, including failed ones (up to 6 tries) and analyses that got stuck. */
    fun toAnalyse(limit: Int): List<Meal> {
        val stale = System.currentTimeMillis() - 10 * 60_000
        return meals(
            "SELECT * FROM meals WHERE status='PENDING' OR (status='FAILED' AND attempts<6) " +
                "OR (status='ANALYSING' AND updated_at<?) ORDER BY taken_at LIMIT $limit",
            stale.toString(),
        )
    }

    fun maxFailedAttempts(): Int =
        readableDatabase.rawQuery("SELECT MAX(attempts) FROM meals WHERE status='FAILED' AND attempts<6", null)
            .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else 0 }

    fun setStatus(id: Long, status: String, error: String? = null, bumpAttempts: Boolean = false) {
        writableDatabase.execSQL(
            "UPDATE meals SET status=?, error=?, attempts=attempts+?, updated_at=? WHERE id=?",
            arrayOf(status, error, if (bumpAttempts) 1 else 0, System.currentTimeMillis(), id),
        )
        changed()
    }

    fun saveAnalysis(id: Long, a: Analysis, json: String, model: String) {
        val v = ContentValues().apply {
            put("status", if (a.isFood) Status.DONE else Status.NOT_FOOD)
            putNull("error")
            put("name", a.mealName)
            put("meal_type", a.mealType)
            put("calories", a.nutrients["calories_kcal"])
            put("protein", a.nutrients["protein_g"])
            put("carbs", a.nutrients["carbs_g"])
            put("fat", a.nutrients["fat_g"])
            put("fiber", a.nutrients["fiber_g"])
            put("health_score", a.healthScore)
            put("analysis_json", json)
            put("model", model)
            put("analysed_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("meals", v, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Saves the user's correction and queues the meal to be analysed again. */
    fun requeue(id: Long, note: String) {
        writableDatabase.execSQL(
            "UPDATE meals SET note=?, status='PENDING', attempts=0, error=NULL, updated_at=? WHERE id=?",
            arrayOf(note.trim().ifEmpty { null }, System.currentTimeMillis(), id),
        )
        changed()
    }

    /** Hides the meal but keeps the row so the gallery photo isn't imported again. */
    fun markDeleted(id: Long) = setStatus(id, Status.DELETED)

    fun days(limit: Int): List<DaySummary> = readableDatabase.rawQuery(
        "SELECT date, COUNT(*), SUM(calories), SUM(protein), SUM(fiber), GROUP_CONCAT(thumb_path, '|') " +
            "FROM meals WHERE status='DONE' GROUP BY date ORDER BY date DESC LIMIT $limit",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                DaySummary(
                    date = c.getString(0),
                    meals = c.getInt(1),
                    kcal = c.getDouble(2),
                    protein = c.getDouble(3),
                    fiber = c.getDouble(4),
                    thumbs = c.getString(5)?.split('|')?.filter { it.isNotBlank() }.orEmpty(),
                )
            )
        }
    }

    // ---- watch activity ----

    fun saveActivity(a: ActivityDay) {
        val v = ContentValues().apply {
            put("date", a.date)
            put("steps", a.steps)
            put("distance_m", a.distanceM)
            put("active_kcal", a.activeKcal)
            put("total_kcal", a.totalKcal)
            put("exercise_min", a.exerciseMin)
            put("avg_hr", a.avgHr)
            put("min_hr", a.minHr)
            put("max_hr", a.maxHr)
            put("resting_hr", a.restingHr)
            put("sleep_min", a.sleepMin)
            put("workouts_json", JSONArray(a.workouts.map { it.toJson() }).toString())
            put("updated_at", a.updatedAt)
        }
        writableDatabase.insertWithOnConflict("activity_days", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        changed()
    }

    fun activity(date: String): ActivityDay? =
        activities("SELECT * FROM activity_days WHERE date=?", date).firstOrNull()

    fun activityBetween(from: String, to: String): List<ActivityDay> =
        activities("SELECT * FROM activity_days WHERE date>=? AND date<=? ORDER BY date", from, to)

    // ---- insights ----

    fun saveInsight(json: String) {
        writableDatabase.insert("insights", null, ContentValues().apply {
            put("created_at", System.currentTimeMillis())
            put("json", json)
        })
        changed()
    }

    fun latestInsight(): Insight? =
        readableDatabase.rawQuery("SELECT created_at, json FROM insights ORDER BY id DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) runCatching { Insight(c.getLong(0), JSONObject(c.getString(1))) }.getOrNull() else null
        }

    // ---- helpers ----

    private fun meals(sql: String, vararg args: String): List<Meal> =
        readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toMeal()) } }

    private fun activities(sql: String, vararg args: String): List<ActivityDay> =
        readableDatabase.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.toActivity()) } }

    private fun Cursor.toMeal() = Meal(
        id = long("id")!!,
        mediaId = long("media_id"),
        takenAt = long("taken_at")!!,
        date = str("date")!!,
        lat = dbl("lat"),
        lon = dbl("lon"),
        photoPath = str("photo_path"),
        thumbPath = str("thumb_path"),
        status = str("status")!!,
        error = str("error"),
        attempts = long("attempts")?.toInt() ?: 0,
        note = str("note"),
        name = str("name"),
        mealType = str("meal_type"),
        calories = dbl("calories"),
        protein = dbl("protein"),
        carbs = dbl("carbs"),
        fat = dbl("fat"),
        fiber = dbl("fiber"),
        healthScore = long("health_score")?.toInt(),
        analysisJson = str("analysis_json"),
        model = str("model"),
        analysedAt = long("analysed_at"),
    )

    private fun Cursor.toActivity() = ActivityDay(
        date = str("date")!!,
        steps = long("steps"),
        distanceM = dbl("distance_m"),
        activeKcal = dbl("active_kcal"),
        totalKcal = dbl("total_kcal"),
        exerciseMin = long("exercise_min"),
        avgHr = long("avg_hr"),
        minHr = long("min_hr"),
        maxHr = long("max_hr"),
        restingHr = long("resting_hr"),
        sleepMin = long("sleep_min"),
        workouts = str("workouts_json")?.let { s -> JSONArray(s).objects().map { Workout.fromJson(it) } }.orEmpty(),
        updatedAt = long("updated_at") ?: 0,
    )

    private fun Cursor.long(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.dbl(col: String): Double? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getDouble(it) }
    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

    private fun changed() = changes.update { it + 1 }

    companion object {
        /** Ticks on every write so screens can reload. */
        val changes = MutableStateFlow(0L)

        @Volatile private var instance: Db? = null

        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context.applicationContext).also { instance = it } }
    }
}
