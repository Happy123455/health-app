package com.happypipaliya.mealtracker

import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class Kind { ENERGY, MACRO, LIMIT, MICRO }

data class NutrientDef(val key: String, val label: String, val unit: String, val kind: Kind)

/** The nutrients Gemini is asked for. Keys must match the response schema in [Gemini]. */
object Nutrients {
    val all = listOf(
        NutrientDef("calories_kcal", "Calories", "kcal", Kind.ENERGY),
        NutrientDef("protein_g", "Protein", "g", Kind.MACRO),
        NutrientDef("carbs_g", "Carbs", "g", Kind.MACRO),
        NutrientDef("fat_g", "Fat", "g", Kind.MACRO),
        NutrientDef("fiber_g", "Fibre", "g", Kind.MACRO),
        NutrientDef("sugar_g", "Sugar", "g", Kind.LIMIT),
        NutrientDef("saturated_fat_g", "Saturated fat", "g", Kind.LIMIT),
        NutrientDef("cholesterol_mg", "Cholesterol", "mg", Kind.LIMIT),
        NutrientDef("sodium_mg", "Sodium", "mg", Kind.LIMIT),
        NutrientDef("potassium_mg", "Potassium", "mg", Kind.MICRO),
        NutrientDef("calcium_mg", "Calcium", "mg", Kind.MICRO),
        NutrientDef("iron_mg", "Iron", "mg", Kind.MICRO),
        NutrientDef("magnesium_mg", "Magnesium", "mg", Kind.MICRO),
        NutrientDef("zinc_mg", "Zinc", "mg", Kind.MICRO),
        NutrientDef("phosphorus_mg", "Phosphorus", "mg", Kind.MICRO),
        NutrientDef("selenium_mcg", "Selenium", "mcg", Kind.MICRO),
        NutrientDef("vitamin_a_mcg", "Vitamin A", "mcg", Kind.MICRO),
        NutrientDef("vitamin_c_mg", "Vitamin C", "mg", Kind.MICRO),
        NutrientDef("vitamin_d_mcg", "Vitamin D", "mcg", Kind.MICRO),
        NutrientDef("vitamin_e_mg", "Vitamin E", "mg", Kind.MICRO),
        NutrientDef("vitamin_k_mcg", "Vitamin K", "mcg", Kind.MICRO),
        NutrientDef("thiamin_mg", "Vitamin B1", "mg", Kind.MICRO),
        NutrientDef("riboflavin_mg", "Vitamin B2", "mg", Kind.MICRO),
        NutrientDef("niacin_mg", "Vitamin B3", "mg", Kind.MICRO),
        NutrientDef("vitamin_b6_mg", "Vitamin B6", "mg", Kind.MICRO),
        NutrientDef("vitamin_b12_mcg", "Vitamin B12", "mcg", Kind.MICRO),
        NutrientDef("folate_mcg", "Folate", "mcg", Kind.MICRO),
    )
    val byKey = all.associateBy { it.key }
}

/**
 * Daily reference amounts. With sex (and age) set it uses adult or teen RDAs/AIs; otherwise the
 * food-label Daily Values. A target of 0 means "no target" (e.g. total sugar).
 */
object Targets {
    fun forProfile(p: Prefs): Map<String, Double> {
        val male = p.sex == "male"
        val female = p.sex == "female"
        val teen = p.age?.let { it in 14..18 } == true
        fun pick(m: Double, f: Double, unknown: Double) = when {
            male -> m
            female -> f
            else -> unknown
        }
        val kcal = calories(p)
        return mapOf(
            "calories_kcal" to kcal,
            "protein_g" to (p.weightKg?.let { (it * 1.2).roundToInt().toDouble() } ?: 50.0),
            "carbs_g" to (kcal * 0.50 / 4).roundToInt().toDouble(),
            "fat_g" to (kcal * 0.30 / 9).roundToInt().toDouble(),
            "fiber_g" to (kcal / 1000 * 14).roundToInt().toDouble(),
            "sugar_g" to 0.0,
            "saturated_fat_g" to (kcal * 0.10 / 9).roundToInt().toDouble(),
            "cholesterol_mg" to 300.0,
            "sodium_mg" to 2000.0,
            "potassium_mg" to if (teen) pick(3000.0, 2300.0, 4700.0) else pick(3400.0, 2600.0, 4700.0),
            "calcium_mg" to if (teen || p.sex.isEmpty()) 1300.0 else 1000.0,
            "iron_mg" to if (teen) pick(11.0, 15.0, 18.0) else pick(8.0, 18.0, 18.0),
            "magnesium_mg" to if (teen) pick(410.0, 360.0, 420.0) else pick(400.0, 310.0, 420.0),
            "zinc_mg" to if (teen) pick(11.0, 9.0, 11.0) else pick(11.0, 8.0, 11.0),
            "phosphorus_mg" to if (teen || p.sex.isEmpty()) 1250.0 else 700.0,
            "selenium_mcg" to 55.0,
            "vitamin_a_mcg" to pick(900.0, 700.0, 900.0),
            "vitamin_c_mg" to if (teen) pick(75.0, 65.0, 90.0) else pick(90.0, 75.0, 90.0),
            "vitamin_d_mcg" to if (p.sex.isEmpty()) 20.0 else 15.0,
            "vitamin_e_mg" to 15.0,
            "vitamin_k_mcg" to if (teen) 75.0 else pick(120.0, 90.0, 120.0),
            "thiamin_mg" to if (teen) pick(1.2, 1.0, 1.2) else pick(1.2, 1.1, 1.2),
            "riboflavin_mg" to if (teen) pick(1.3, 1.0, 1.3) else pick(1.3, 1.1, 1.3),
            "niacin_mg" to pick(16.0, 14.0, 16.0),
            "vitamin_b6_mg" to if (teen) pick(1.3, 1.2, 1.7) else pick(1.3, 1.3, 1.7),
            "vitamin_b12_mcg" to 2.4,
            "folate_mcg" to 400.0,
        )
    }

    /** Mifflin-St Jeor x 1.55 (training most days) when the profile is complete, else 2,000 kcal. */
    private fun calories(p: Prefs): Double {
        val age = p.age ?: return 2000.0
        val h = p.heightCm ?: return 2000.0
        val w = p.weightKg ?: return 2000.0
        if (p.sex.isEmpty()) return 2000.0
        val bmr = 10 * w + 6.25 * h - 5 * age + if (p.sex == "male") 5 else -161
        return ((bmr * 1.55) / 50).roundToInt() * 50.0
    }
}

fun sumNutrients(meals: List<Meal>): Map<String, Double> =
    Nutrients.all.associate { d -> d.key to meals.sumOf { it.analysis?.nutrients?.get(d.key) ?: 0.0 } }

/** "1,240" for big numbers, "4.5" for small ones, "0.45" for tiny ones. */
fun fmt(v: Double?): String {
    if (v == null || v.isNaN()) return "–"
    return when {
        v >= 10 -> String.format(Locale.UK, "%,d", v.roundToLong())
        v >= 1 -> String.format(Locale.UK, "%.1f", v).removeSuffix(".0")
        v == 0.0 -> "0"
        else -> String.format(Locale.UK, "%.2f", v).trimEnd('0').removeSuffix(".")
    }
}

fun fmtInt(v: Number?): String = v?.let { String.format(Locale.UK, "%,d", it.toLong()) } ?: "–"

fun fmtMinutes(min: Long?): String = when {
    min == null -> "–"
    min < 60 -> "$min min"
    else -> "${min / 60} h ${min % 60} min"
}
