package com.happypipaliya.mealtracker

import android.content.Context
import android.util.Log
import com.happypipaliya.sleepcore.Fruit
import com.happypipaliya.sleepcore.FruitGuide
import com.happypipaliya.sleepcore.FruitPick
import com.happypipaliya.sleepcore.FruitPrice
import com.happypipaliya.sleepcore.MealPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Seasonal fruit picks (logic in sleepcore/Fruit.kt) and daily mandi prices from Agmarknet's
 * "current daily price" dataset on data.gov.in. The feed only holds today's prices, so each fetch
 * is stored to build up a history for trends.
 */
object FruitEngine {
    private const val TAG = "FruitEngine"
    private const val RESOURCE = "https://api.data.gov.in/resource/9ef84268-d588-465a-a308-a864a43d0070"
    /** data.gov.in's public sample key (10 rows per request). */
    private const val SAMPLE_KEY = "579b464db66ec23bdd000001cdd3946e44ce4aad7209ff7b23ac571b"
    private const val HOME_DISTRICT = "Rajkot"
    private val API_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    private val key get() = BuildConfig.DATA_GOV_KEY.ifBlank { SAMPLE_KEY }

    private fun get(filters: Map<String, String>, offset: Int, limit: Int): JSONObject {
        val q = buildString {
            append("?api-key=").append(key).append("&format=json&offset=").append(offset).append("&limit=").append(limit)
            filters.forEach { (k, v) -> append("&filters%5B").append(k).append("%5D=").append(URLEncoder.encode(v, "UTF-8")) }
        }
        val c = URL(RESOURCE + q).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        return try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    private fun rows(o: JSONObject): List<Db.PriceRow> = o.optJSONArray("records").objects().mapNotNull { r ->
        val date = runCatching { LocalDate.parse(r.optString("arrival_date"), API_DATE).toString() }.getOrNull() ?: return@mapNotNull null
        Db.PriceRow(date, r.optString("district"), r.optString("market"), r.optString("commodity"), r.optString("variety"),
            r.optDouble("min_price"), r.optDouble("modal_price"), r.optDouble("max_price"))
    }

    /** Pages through every row for the home district, then looks Gujarat-wide for in-season fruit it didn't have. */
    suspend fun fetchPrices(ctx: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val p = Prefs(ctx)
        if (!force && System.currentTimeMillis() - p.pricesFetchedAt < 6 * 3_600_000L) return@withContext true
        val limit = if (BuildConfig.DATA_GOV_KEY.isBlank()) 10 else 100
        runCatching {
            val found = ArrayList<Db.PriceRow>()
            var offset = 0
            while (offset < 400) {
                val o = get(mapOf("state.keyword" to "Gujarat", "district" to HOME_DISTRICT), offset, limit)
                val page = rows(o)
                found += page
                offset += limit
                if (page.size < limit || offset >= o.optInt("total", 0)) break
            }
            val have = found.mapNotNull { FruitGuide.forCommodity(it.commodity)?.key }.toSet()
            val month = LocalDate.now().monthValue
            for (f in FruitGuide.inSeason(month).filter { it.key !in have }) {
                for (name in f.mandi.take(1)) {
                    val commodity = AGMARK_NAMES[f.key] ?: name
                    runCatching { found += rows(get(mapOf("state.keyword" to "Gujarat", "commodity" to commodity), 0, limit)) }
                }
            }
            Db.get(ctx).savePrices(found)
            p.pricesFetchedAt = System.currentTimeMillis()
            Log.i(TAG, "saved ${found.size} mandi rows")
        }.onFailure { Log.w(TAG, "price fetch failed", it) }.isSuccess
    }

    /** Agmarknet's exact commodity spellings, for the Gujarat-wide lookups. */
    private val AGMARK_NAMES = mapOf(
        "chikoo" to "Chikoos(Sapota)", "sitafal" to "Custard Apple (Sharifa)", "mosambi" to "Mousambi(Sweet Lime)",
        "amla" to "Amla(Nelli Kai)", "ber" to "Ber(Zizyphus/Borehannu)", "watermelon" to "Water Melon",
        "muskmelon" to "Karbuja(Musk Melon)", "jamun" to "Jamun(Narale Hannu)", "guava" to "Guava", "pomegranate" to "Pomegranate",
        "banana" to "Banana", "papaya" to "Papaya", "orange" to "Orange", "apple" to "Apple", "grapes" to "Grapes",
        "mango" to "Mango", "strawberry" to "Strawberry",
    )

    /** "Gondal(Veg.market Gondal) APMC" -> "Gondal". */
    fun marketName(m: String) = m.substringBefore("(").removeSuffix(" APMC").trim()

    data class PriceInfo(val now: FruitPrice, val history: List<Pair<String, Double>>, val local: Boolean)

    /** Latest price per fruit (home district preferred, then elsewhere in Gujarat) and its daily history. */
    fun prices(ctx: Context): Map<String, PriceInfo> {
        val rows = Db.get(ctx).pricesSince(LocalDate.now().minusDays(45).toString())
            .mapNotNull { r -> FruitGuide.forCommodity(r.commodity)?.let { it to r } }
            .filter { (_, r) -> r.modal > 0 && !r.variety.contains("raw", true) && !r.variety.contains("green", true) }
        return rows.groupBy({ it.first.key }, { it.second }).mapValues { (_, list) ->
            val local = list.filter { it.district.equals(HOME_DISTRICT, true) }
            val use = local.ifEmpty { list }
            val history = use.groupBy { it.date }.map { (d, rs) -> d to rs.map { it.modal }.sorted()[rs.size / 2] / 100 }.sortedBy { it.first }
            val latest = use.maxBy { it.date }
            val sameDay = use.filter { it.date == latest.date }
            PriceInfo(
                FruitPrice(history.last().second, sameDay.minOf { it.min } / 100, sameDay.maxOf { it.max } / 100, marketName(latest.market), latest.date),
                history, local.isNotEmpty(),
            )
        }
    }

    data class FruitReport(val month: Int, val picks: List<FruitPick>, val prices: Map<String, PriceInfo>, val fetchedAt: Long)

    suspend fun report(ctx: Context): FruitReport = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val p = Prefs(ctx)
        val targets = Targets.forProfile(p)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        // Average daily intake over the last week's logged days vs targets.
        val meals = db.doneMealsBetween(today.minusDays(6).toString(), today.toString())
        val loggedDays = meals.map { it.date }.distinct().size.coerceAtLeast(1)
        val gaps = targets.mapValues { (k, t) ->
            if (t <= 0) 0.0 else (1 - meals.sumOf { it.analysis?.nutrients?.get(k) ?: 0.0 } / loggedDays / t).coerceIn(0.0, 1.0)
        }
        val last = meals.filter { it.takenAt <= now }.maxByOrNull { it.takenAt }?.analysis?.nutrients
        val lastAt = meals.maxOfOrNull { it.takenAt } ?: 0L
        val ironNoC = last != null && now - lastAt < 3 * 3_600_000L && (last["iron_mg"] ?: 0.0) >= 3 && (last["vitamin_c_mg"] ?: 0.0) < 25
        val workouts = db.activityBetween(today.minusDays(8).toString(), today.toString()).flatMap { it.workouts }
        val trained = workouts.any { now - it.end in 0..4 * 3_600_000L } ||
            MealPlan.repeatWorkouts(workouts.map { com.happypipaliya.sleepcore.Exercise(it.start, it.end, it.kcal, it.avgHr) }, now).any { it.start - now < 6 * 3_600_000L }
        val prices = prices(ctx)
        FruitReport(
            today.monthValue,
            FruitGuide.suggest(today.monthValue, targets, gaps, prices.mapValues { it.value.now }, ironNoC, trained),
            prices, p.pricesFetchedAt,
        )
    }

    /** One line for Gemini prompts. */
    fun seasonLine(): String {
        val m = LocalDate.now().monthValue
        return "Fruit in season in Gujarat now: " + FruitGuide.inSeason(m).joinToString { "${it.name} (${it.local})" } + "."
    }

    fun bestVitaminC(month: Int): List<Fruit> = FruitGuide.inSeason(month).sortedByDescending { FruitGuide.perServing(it, "vitamin_c_mg") }.take(2)
}
