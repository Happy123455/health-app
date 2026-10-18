package com.happypipaliya.sleepcore

/*
 * Seasonal fruit for Gujarat (Saurashtra) and what each one is good for.
 *
 * Seasons are when the fruit is plentiful and cheapest in Rajkot-area markets: local crops (Gir
 * Kesar mango, Saurashtra custard apple, Kutch pomegranate, Valsad chikoo) plus fruit that arrives
 * in bulk from elsewhere (Nagpur orange, Himachal/Kashmir apple). Nutrition is per 100 g of the
 * edible part, rounded from USDA FoodData Central and the Indian Food Composition Tables (IFCT 2017).
 */

data class Fruit(
    val key: String,
    val name: String,
    val local: String,        // Gujarati name in Latin script
    val emoji: String?,
    val color: Long,          // ARGB, for the badge when there's no emoji
    val months: Set<Int>,     // 1-12, in season
    val peak: Set<Int>,       // the best (cheapest, tastiest) part of it
    val servingG: Int,        // a typical serving
    val serving: String,
    val mandi: List<String>,  // commodity names used by Agmarknet, lowercase
    val per100: Map<String, Double>,
    val note: String,
)

/** One mandi price, in rupees per kg (Agmarknet reports per quintal). */
data class FruitPrice(val perKg: Double, val minKg: Double, val maxKg: Double, val market: String, val date: String)

data class FruitPick(val fruit: Fruit, val reason: String, val score: Double, val price: FruitPrice?)

object FruitGuide {
    private fun n(kcal: Double, carbs: Double, fiber: Double, protein: Double, vitC: Double, k: Double, fe: Double = 0.3, vitA: Double = 0.0, folate: Double = 0.0, mg: Double = 10.0, vitK: Double = 0.0) = mapOf(
        "calories_kcal" to kcal, "carbs_g" to carbs, "fiber_g" to fiber, "protein_g" to protein, "vitamin_c_mg" to vitC,
        "potassium_mg" to k, "iron_mg" to fe, "vitamin_a_mcg" to vitA, "folate_mcg" to folate, "magnesium_mg" to mg, "vitamin_k_mcg" to vitK,
    )

    private val ALL_YEAR = (1..12).toSet()

    val ALL = listOf(
        Fruit("guava", "Guava", "Jamfal", null, 0xFF8BC34A, setOf(8, 9, 10, 11, 12, 1, 2, 3), setOf(11, 12, 1), 150, "1 guava",
            listOf("guava"), n(68.0, 14.3, 5.4, 2.6, 228.0, 417.0, folate = 49.0, vitA = 31.0, mg = 22.0, vitK = 2.6),
            "One guava has more than two days' vitamin C, plus lots of fibre."),
        Fruit("chikoo", "Chikoo", "Chikoo", null, 0xFF8D6E63, setOf(10, 11, 12, 1, 2, 3, 4, 5, 6), setOf(11, 12, 1, 2), 150, "1 large chikoo",
            listOf("chikoos(sapota)", "sapota", "chikoo"), n(83.0, 20.0, 5.3, 0.4, 14.7, 193.0, fe = 0.8, mg = 12.0),
            "Sweet, fibre-rich quick energy. Good before a workout."),
        Fruit("sitafal", "Custard apple", "Sitafal", null, 0xFF9CCC65, setOf(9, 10, 11), setOf(10), 150, "1 medium",
            listOf("custard apple (sharifa)", "custard apple"), n(94.0, 23.6, 4.4, 2.1, 36.0, 247.0, fe = 0.6, mg = 21.0),
            "Saurashtra's autumn fruit: potassium and magnesium with fast carbs."),
        Fruit("pomegranate", "Pomegranate", "Dadam", null, 0xFFE53935, setOf(9, 10, 11, 12, 1, 2, 3), setOf(10, 11, 12), 150, "1 cup seeds",
            listOf("pomegranate"), n(83.0, 18.7, 4.0, 1.7, 10.2, 236.0, folate = 38.0, mg = 12.0, vitK = 16.4),
            "Folate and vitamin K, with plenty of fibre in the seeds."),
        Fruit("banana", "Banana", "Kela", "🍌", 0xFFFFEB3B, ALL_YEAR, ALL_YEAR, 120, "1 banana",
            listOf("banana"), n(89.0, 22.8, 2.6, 1.1, 8.7, 358.0, mg = 27.0),
            "Potassium and easy carbs. The classic pre- or post-workout fruit."),
        Fruit("papaya", "Papaya", "Papaiyu", null, 0xFFFF9800, ALL_YEAR, setOf(10, 11, 12, 1, 2, 3), 200, "1 bowl",
            listOf("papaya"), n(43.0, 10.8, 1.7, 0.5, 61.0, 182.0, vitA = 47.0, folate = 37.0, mg = 21.0, vitK = 2.6),
            "Light, with vitamin C and vitamin A, and easy on digestion."),
        Fruit("orange", "Orange", "Santra", "🍊", 0xFFFF9800, setOf(10, 11, 12, 1, 2, 3), setOf(12, 1), 150, "1 orange",
            listOf("orange"), n(47.0, 11.8, 2.4, 0.9, 53.0, 181.0, folate = 30.0, vitA = 11.0),
            "Nagpur oranges: vitamin C and folate."),
        Fruit("mosambi", "Sweet lime", "Mosambi", "🍋", 0xFFCDDC39, setOf(7, 8, 9, 10, 11, 12, 1, 2, 3), setOf(10, 11, 12), 150, "1 mosambi",
            listOf("mousambi(sweet lime)", "mousambi", "mosambi"), n(43.0, 9.3, 0.5, 0.8, 50.0, 117.0),
            "Vitamin C and fluids. Juice loses the fibre, so eat it whole."),
        Fruit("apple", "Apple", "Safarjan", "🍎", 0xFFD32F2F, setOf(8, 9, 10, 11, 12, 1, 2), setOf(9, 10, 11), 150, "1 apple",
            listOf("apple"), n(52.0, 13.8, 2.4, 0.3, 4.6, 107.0, vitK = 2.2),
            "Filling, with soluble fibre. Cheapest in autumn when the Himachal crop arrives."),
        Fruit("amla", "Amla", "Amla", null, 0xFF7CB342, setOf(11, 12, 1, 2, 3), setOf(12, 1), 50, "1-2 amla",
            listOf("amla(nelli kai)", "amla"), n(44.0, 10.2, 4.3, 0.9, 250.0, 198.0, mg = 10.0),
            "The strongest vitamin C source here. Great with an iron-rich meal."),
        Fruit("ber", "Ber", "Bor", null, 0xFFC0CA33, setOf(12, 1, 2, 3), setOf(1, 2), 100, "a handful",
            listOf("ber(zizyphus/borehannu)", "ber"), n(79.0, 20.2, 1.5, 1.2, 69.0, 250.0, fe = 0.5, mg = 10.0),
            "Winter berry with vitamin C and potassium."),
        Fruit("grapes", "Grapes", "Draksh", "🍇", 0xFF7B1FA2, setOf(1, 2, 3, 4), setOf(2, 3), 150, "1 cup",
            listOf("grapes"), n(69.0, 18.1, 0.9, 0.7, 3.2, 191.0, mg = 7.0, vitK = 14.6),
            "Nashik grapes: quick energy and vitamin K."),
        Fruit("watermelon", "Watermelon", "Tarbuch", "🍉", 0xFFE57373, setOf(3, 4, 5, 6), setOf(4, 5), 300, "2 slices",
            listOf("water melon", "watermelon"), n(30.0, 7.6, 0.4, 0.6, 8.1, 112.0, vitA = 28.0, mg = 10.0),
            "Over 90% water: summer hydration with few calories."),
        Fruit("muskmelon", "Muskmelon", "Sakar teti", "🍈", 0xFFFFB74D, setOf(3, 4, 5, 6), setOf(4, 5), 200, "1 bowl",
            listOf("karbuja(musk melon)", "musk melon", "muskmelon"), n(34.0, 8.2, 0.9, 0.8, 36.7, 267.0, vitA = 169.0, folate = 21.0, mg = 12.0),
            "Vitamin A, vitamin C and potassium, with lots of water."),
        Fruit("mango", "Kesar mango", "Keri", "🥭", 0xFFFFA000, setOf(4, 5, 6, 7), setOf(5, 6), 150, "1 small mango",
            listOf("mango", "mango (raw-ripe)"), n(60.0, 15.0, 1.6, 0.8, 36.4, 168.0, vitA = 54.0, folate = 43.0, mg = 10.0, vitK = 4.2),
            "Gir's Kesar: vitamin A, vitamin C and folate. Pair it with protein so the sugar comes on slower."),
        Fruit("jamun", "Jamun", "Jambu", null, 0xFF4A148C, setOf(6, 7), setOf(6, 7), 100, "a handful",
            listOf("jamun(narale hannu)", "jamun"), n(60.0, 15.6, 0.6, 0.7, 14.3, 79.0, fe = 0.2, mg = 15.0),
            "A short monsoon season. Lower in sugar than most fruit."),
        Fruit("strawberry", "Strawberry", "Strawberry", "🍓", 0xFFE91E63, setOf(12, 1, 2), setOf(1), 150, "1 cup",
            listOf("strawberry"), n(32.0, 7.7, 2.0, 0.7, 58.8, 153.0, fe = 0.4, folate = 24.0, mg = 13.0, vitK = 2.2),
            "Mahabaleshwar winter crop: vitamin C with few calories."),
    )

    val byKey = ALL.associateBy { it.key }

    /** Which fruit an Agmarknet commodity name is, or null. */
    fun forCommodity(commodity: String): Fruit? {
        val c = commodity.trim().lowercase()
        return ALL.firstOrNull { f -> f.mandi.any { m -> c == m || c.startsWith("$m(") || c.startsWith("$m ") } }
    }

    fun inSeason(month: Int): List<Fruit> = ALL.filter { month in it.months }
    fun arriving(month: Int): List<Fruit> = ALL.filter { month !in it.months && (month % 12 + 1) in it.months }

    private val LABELS = mapOf(
        "vitamin_c_mg" to "vitamin C", "potassium_mg" to "potassium", "fiber_g" to "fibre", "folate_mcg" to "folate",
        "vitamin_a_mcg" to "vitamin A", "magnesium_mg" to "magnesium", "vitamin_k_mcg" to "vitamin K", "iron_mg" to "iron",
    )
    private val UNITS = mapOf("vitamin_c_mg" to "mg", "potassium_mg" to "mg", "fiber_g" to "g", "folate_mcg" to "mcg", "vitamin_a_mcg" to "mcg", "magnesium_mg" to "mg", "vitamin_k_mcg" to "mcg", "iron_mg" to "mg")

    /** Amount of a nutrient in one serving. */
    fun perServing(f: Fruit, key: String) = (f.per100[key] ?: 0.0) * f.servingG / 100.0

    /**
     * Picks for today from what's in season.
     * [gaps]: nutrient key to how far short you are (0 = on target, 1 = nothing eaten), e.g. from the energy model.
     * [prices]: today's mandi price per fruit key, when known.
     * [ironMealWithoutC]: you just ate an iron-rich meal with little vitamin C.
     * [trainedRecently]: a workout in the last few hours or coming up (favours carbs + potassium).
     * [hot]: hot-weather month (favours watery fruit).
     */
    fun suggest(
        month: Int,
        targets: Map<String, Double>,
        gaps: Map<String, Double>,
        prices: Map<String, FruitPrice>,
        ironMealWithoutC: Boolean = false,
        trainedRecently: Boolean = false,
        hot: Boolean = month in 4..6,
    ): List<FruitPick> {
        val options = inSeason(month)
        val cheapest = options.mapNotNull { prices[it.key]?.perKg }.minOrNull()
        return options.map { f ->
            // How much of each gap one serving closes.
            val contributions = LABELS.keys.associateWith { k ->
                val t = targets[k] ?: return@associateWith 0.0
                if (t <= 0) 0.0 else (perServing(f, k) / t).coerceAtMost(1.0) * (0.25 + (gaps[k] ?: 0.0))
            }
            var score = contributions.values.sum()
            if (month in f.peak) score += 0.15
            if (ironMealWithoutC) score += (perServing(f, "vitamin_c_mg") / 60.0).coerceAtMost(1.0) * 0.6
            if (trainedRecently) score += (perServing(f, "carbs_g") / 30.0).coerceAtMost(1.0) * 0.6 + (perServing(f, "potassium_mg") / 400.0).coerceAtMost(1.0) * 0.3
            if (hot && f.key in setOf("watermelon", "muskmelon", "mosambi")) score += 0.3
            val price = prices[f.key]
            if (price != null && cheapest != null) score += 0.15 * (cheapest / price.perKg).coerceIn(0.0, 1.0)
            val best = contributions.maxBy { it.value }.key
            val general = "${f.serving} gives ~${fmtAmt(perServing(f, best))} ${UNITS[best]} ${LABELS[best]}" +
                ((gaps[best] ?: 0.0).takeIf { it >= 0.3 }?.let { ", which you've been short on" } ?: "") + "."
            val special = when {
                ironMealWithoutC && perServing(f, "vitamin_c_mg") >= 40 ->
                    "iron" to "${f.serving} has ~${perServing(f, "vitamin_c_mg").toInt()} mg vitamin C, which helps you absorb the iron from your last meal."
                trainedRecently && f.key in setOf("banana", "chikoo", "sitafal") ->
                    "training" to "${f.serving}: carbs and potassium to refuel after training."
                hot && f.key in setOf("watermelon", "muskmelon") -> "heat" to "${f.serving}: mostly water, good for hot days."
                else -> null
            }
            Triple(FruitPick(f, general, score, price), special, score)
        }.sortedByDescending { it.third }.let { ranked ->
            // Only the top pick for a situation gets the situational reason; the rest say what they're good for.
            val used = HashSet<String>()
            ranked.map { (pick, special, _) ->
                if (special != null && used.add(special.first)) pick.copy(reason = special.second) else pick
            }
        }
    }

    private fun fmtAmt(v: Double) = if (v >= 10) v.toInt().toString() else "%.1f".format(v)
}
