package com.happypipaliya.mealtracker

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Gallery album (folder name) whose photos are analysed automatically. */
    var albumName: String
        get() = sp.getString("album", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_ALBUM
        set(v) = sp.edit { putString("album", v.trim()) }

    var age: Int?
        get() = sp.getInt("age", 0).takeIf { it > 0 }
        set(v) = sp.edit { putInt("age", v ?: 0) }

    /** "male", "female" or "" when not set. */
    var sex: String
        get() = sp.getString("sex", "")!!
        set(v) = sp.edit { putString("sex", v) }

    var heightCm: Double?
        get() = sp.getFloat("height", 0f).takeIf { it > 0f }?.toDouble()
        set(v) = sp.edit { putFloat("height", (v ?: 0.0).toFloat()) }

    var weightKg: Double?
        get() = sp.getFloat("weight", 0f).takeIf { it > 0f }?.toDouble()
        set(v) = sp.edit { putFloat("weight", (v ?: 0.0).toFloat()) }

    var goal: String
        get() = sp.getString("goal", null) ?: "General fitness"
        set(v) = sp.edit { putString("goal", v) }

    var trainingPlan: String
        get() = sp.getString("plan", null) ?: DEFAULT_PLAN
        set(v) = sp.edit { putString("plan", v) }

    /** Free text such as "vegetarian, no mushrooms". */
    var foodNotes: String
        get() = sp.getString("food_notes", "")!!
        set(v) = sp.edit { putString("food_notes", v) }

    /** Save each analysed meal to Samsung Health's food log (through Health Connect). */
    var writeNutritionToHealthConnect: Boolean
        get() = sp.getBoolean("hc_write", true)
        set(v) = sp.edit { putBoolean("hc_write", v) }

    /** A nudge ~15 min after eating: walk now, chai later for iron-rich meals. */
    var digestionReminders: Boolean
        get() = sp.getBoolean("digest_remind", true)
        set(v) = sp.edit { putBoolean("digest_remind", v) }

    var notifications: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) = sp.edit { putBoolean("notify", v) }

    /** Last attempt to write a meal to Health Connect: time and error (empty = success). */
    var hcWriteAt: Long
        get() = sp.getLong("hc_write_at", 0L)
        set(v) = sp.edit { putLong("hc_write_at", v) }
    var hcWriteError: String
        get() = sp.getString("hc_write_err", "")!!
        set(v) = sp.edit { putString("hc_write_err", v) }

    /** When mandi prices were last fetched (epoch ms). */
    var pricesFetchedAt: Long
        get() = sp.getLong("prices_at", 0L)
        set(v) = sp.edit { putLong("prices_at", v) }

    /** Watch smart alarm: wake window (encoded [com.happypipaliya.sleepcore.AlarmSettings]). */
    var smartAlarm: String?
        get() = sp.getString("smart_alarm", null)
        set(v) = sp.edit { putString("smart_alarm", v) }

    /** Latest trained wake model (encoded [com.happypipaliya.sleepcore.Model]). */
    var wakeModel: String?
        get() = sp.getString("wake_model", null)
        set(v) = sp.edit { putString("wake_model", v) }

    companion object {
        const val DEFAULT_ALBUM = "Meals"
        const val DEFAULT_PLAN = "Mon chest & triceps, Tue back & biceps, Wed legs, Thu shoulders, " +
            "Fri core, Sat cardio, Sun rest. Bodyweight, with a school bag as the only weight."
    }
}
