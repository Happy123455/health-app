package com.happypipaliya.mealtracker

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    /** Tried in order; later ones are used only when an earlier one is overloaded or out of quota. */
    // Each model has its own free daily limit (20 requests), so more fallbacks = more meals per day.
    val MODELS = listOf("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash", "gemini-2.5-flash", "gemini-3.1-flash-lite")

    class ApiException(val code: Int, message: String) : Exception(message) {
        /** Network problems, rate limits and overloads are worth retrying later; bad requests are not. */
        val retryable: Boolean get() = code == 0 || code == 408 || code == 429 || code >= 500

        /** The free tier allows 20 requests per model per day; 429 means that's used up for now. */
        val quota: Boolean get() = code == 429
    }

    data class Reply(val json: JSONObject, val text: String, val model: String)

    suspend fun generate(body: JSONObject): Reply = withContext(Dispatchers.IO) {
        val errors = mutableListOf<ApiException>()
        for (model in MODELS) {
            try {
                return@withContext call(model, body)
            } catch (e: ApiException) {
                if (!e.retryable) throw e
                errors += e
            }
        }
        if (errors.isNotEmpty() && errors.all { it.quota }) {
            throw ApiException(429, "Gemini's free daily limit is used up for now")
        }
        throw errors.lastOrNull { !it.quota } ?: ApiException(0, "No Gemini model available")
    }

    /** Gemini 2.x doesn't understand `thinkingLevel`, so drop it for those models. */
    private fun forModel(model: String, body: JSONObject): JSONObject {
        if (!model.startsWith("gemini-2.")) return body
        return JSONObject(body.toString()).apply { optJSONObject("generationConfig")?.remove("thinkingConfig") }
    }

    private fun call(model: String, body: JSONObject): Reply {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 150_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            conn.outputStream.use { it.write(forModel(model, body).toString().toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw ApiException(code, msg?.takeIf { it.isNotBlank() } ?: "HTTP $code")
            }
            val candidate = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?: throw ApiException(500, "Gemini returned no answer")
            val out = candidate.optJSONObject("content")?.optJSONArray("parts").objects()
                .filter { !it.optBoolean("thought") }
                .joinToString("") { it.optString("text") }
            if (out.isBlank()) throw ApiException(500, "Empty answer (${candidate.optString("finishReason")})")
            return Reply(JSONObject(out), out, model)
        } catch (e: IOException) {
            throw ApiException(0, "Network error: ${e.message}")
        } catch (e: JSONException) {
            throw ApiException(500, "Unreadable answer from $model")
        } finally {
            conn.disconnect()
        }
    }

    // ---- meal photo ----

    fun mealRequest(jpeg: ByteArray, context: String): JSONObject = request(
        system = MEAL_SYSTEM,
        parts = JSONArray()
            .put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
            .put(JSONObject().put("text", context)),
        schema = mealSchema(),
        thinking = "low",
    )

    private const val MEAL_SYSTEM = """You are the nutrition engine of a personal meal-tracking app. You get one photo plus context about the user and their day.

Rules:
- First decide whether the photo shows food or drink someone is about to eat or drink (packaged food counts). If not (screenshots, people, documents, menus, shop shelves), set is_food=false, meal_name to a 2-4 word description of the photo, empty arrays, 0 for numbers, health_score 1, and empty strings elsewhere.
- Identify every visible item. Estimate portions in grams from visual cues: a dinner plate is about 26 cm, a katori/small bowl holds about 150 ml, one roti is about 35 g; also use cutlery, hands and packaging. Assume the person eats everything shown unless it is clearly shared.
- Estimate nutrients for the whole meal as eaten, using typical home recipes for the cuisine you see, including the oil, ghee, butter or sugar that cooking usually adds. Put the key assumptions in `assumptions` (1-2 sentences).
- `nutrients`: the calories and macros must match the sum of `items`; vitamins and minerals are your best estimate for the whole meal.
- `health_score` (1-10) rates balance and food quality (protein, fibre, vegetables, whole grains, how processed, fried or sugary), not calories alone.
- `highlights`: 1-3 short points on what is good. `watch_outs`: 0-2 short points. Each under 15 words.
- `upgrades`: 2-3 creative, specific ideas that keep the dish tasty and true to itself but make it healthier. Prefer adding or swapping something small that is easy at home. Each has a catchy short `title`, `how` to do it, and `why` it helps.
- `next_meal`: 1-2 sentences on what to eat next to balance the day, using the intake-so-far and activity context. Name specific foods.
- If the user added a note about the photo, trust the note over what you see.
- Tone: friendly and encouraging. Never shame, never suggest crash diets, skipping meals or extreme restriction. If the user is under 18, focus on growth and balanced eating and never suggest cutting calories.
- Use British English spelling. Numbers are plain numbers without units."""

    private fun mealSchema(): JSONObject = obj(
        linkedMapOf(
            "is_food" to type("boolean"),
            "meal_name" to type("string"),
            "meal_type" to type("string").put("enum", JSONArray(listOf("breakfast", "lunch", "dinner", "snack", "drink"))),
            "items" to arr(obj(linkedMapOf(
                "name" to type("string"), "portion" to type("string"), "grams" to type("number"),
                "calories_kcal" to type("number"), "protein_g" to type("number"),
                "carbs_g" to type("number"), "fat_g" to type("number"),
            ))),
            "nutrients" to obj(LinkedHashMap(Nutrients.all.associate { it.key to type("number") })),
            "confidence" to type("string").put("enum", JSONArray(listOf("low", "medium", "high"))),
            "assumptions" to type("string"),
            "health_score" to type("integer").put("minimum", 1).put("maximum", 10),
            "highlights" to arr(type("string")),
            "watch_outs" to arr(type("string")),
            "upgrades" to arr(obj(linkedMapOf("title" to type("string"), "how" to type("string"), "why" to type("string")))),
            "next_meal" to type("string"),
        )
    )

    // ---- patterns across days ----

    fun insightsRequest(data: String): JSONObject = request(
        system = INSIGHTS_SYSTEM,
        parts = JSONArray().put(JSONObject().put("text", data)),
        schema = obj(linkedMapOf(
            "summary" to type("string"),
            "patterns" to arr(obj(linkedMapOf("title" to type("string"), "detail" to type("string")))),
            "wins" to arr(type("string")),
            "focus" to arr(obj(linkedMapOf("nutrient" to type("string"), "why" to type("string"), "foods" to arr(type("string"))))),
            "plan" to arr(obj(linkedMapOf("day" to type("string"), "idea" to type("string")))),
            "one_small_change" to type("string"),
        )),
        thinking = "medium",
    )

    private const val INSIGHTS_SYSTEM = """You are a friendly nutrition coach inside a personal meal-tracking app. You get the user's logged meals (estimated from photos by AI) and watch activity for recent days, plus their profile and daily targets.

- `summary`: 2-3 sentences on how their eating looks overall.
- `patterns`: 3-5 real patterns in the data: timing, repeated foods, weekdays vs weekends, training days vs rest days, nutrients that are often low or high. Each `detail` cites the evidence (days, meals or numbers).
- `wins`: 2-3 things going well.
- `focus`: up to 3 nutrients or habits to improve. For each: why it matters for this person, and 3-5 specific foods that fit the way they already eat.
- `plan`: 7 entries, one per day starting tomorrow (`day` = weekday name). Each is a concrete, tasty idea that closes a gap, ideally an upgrade of a dish they already eat.
- `one_small_change`: the single easiest change with the biggest effect.

Rules: values are photo estimates, so treat small differences as noise and talk about trends. Be friendly and encouraging; never shame; no crash diets, skipped meals or extreme restriction. If the user is under 18, focus on growth and never suggest cutting calories. Use British English."""

    // ---- helpers ----

    private fun request(system: String, parts: JSONArray, schema: JSONObject, thinking: String) = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
        .put("generationConfig", JSONObject()
            .put("responseMimeType", "application/json")
            .put("responseJsonSchema", schema)
            .put("thinkingConfig", JSONObject().put("thinkingLevel", thinking)))

    private fun type(t: String) = JSONObject().put("type", t)

    private fun arr(items: JSONObject) = JSONObject().put("type", "array").put("items", items)

    private fun obj(props: LinkedHashMap<String, JSONObject>): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "object").put("properties", p).put("required", JSONArray(props.keys.toList()))
    }
}
