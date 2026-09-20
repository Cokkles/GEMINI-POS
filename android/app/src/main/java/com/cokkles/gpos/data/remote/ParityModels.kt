package com.cokkles.gpos.data.remote

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

data class CapabilitySnapshot(
    val tasksHistoryV1: Boolean,
    val nutritionHistoryV1: Boolean,
    val advertisedFeatures: Set<String>,
)

object CapabilityPayloadMapper {
    fun map(json: JSONObject): CapabilitySnapshot {
        val root = unwrap(json)
        val ux = root.optJSONObject("ux_contracts") ?: JSONObject()
        val features = root.optJSONObject("features") ?: JSONObject()
        val featureNames = buildSet {
            features.keys().forEach { key -> if (features.optBoolean(key, false)) add(key) }
            ux.keys().forEach { key -> if (ux.optBoolean(key, false)) add(key) }
        }
        return CapabilitySnapshot(
            tasksHistoryV1 = ux.optBoolean("tasks_history_v1", false),
            nutritionHistoryV1 = ux.optBoolean("nutrition_history_v1", false),
            advertisedFeatures = featureNames,
        )
    }
}

data class CalendarRangeEvent(
    val id: String,
    val title: String,
    val start: String?,
    val end: String?,
    val localDate: String?,
    val localTime: String?,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
    val calendarId: String?,
    val calendarName: String?,
    val calendarColor: String?,
    val calendarOwned: Boolean,
)

data class CalendarSource(
    val id: String,
    val name: String,
    val color: String?,
    val owned: Boolean,
    val selected: Boolean,
    val primary: Boolean,
)

data class CalendarRangeSnapshot(
    val startDate: String,
    val endDate: String,
    val events: List<CalendarRangeEvent>,
    val calendars: List<CalendarSource> = emptyList(),
    val includesShared: Boolean = false,
)

object CalendarRangePayloadMapper {
    fun map(json: JSONObject, startDate: String, endDate: String): CalendarRangeSnapshot {
        val root = unwrap(json)
        val array = root.optJSONArray("events") ?: JSONArray()
        val events = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                val start = item.optString("start").trim().takeIf(String::isNotBlank)
                val end = item.optString("end").trim().takeIf(String::isNotBlank)
                val localDate = item.optString("local_date").trim().takeIf(String::isNotBlank)
                    ?: start?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                val localTime = item.optString("local_time").trim().takeIf(String::isNotBlank)
                    ?: item.optString("time").trim().takeIf(String::isNotBlank)
                add(
                    CalendarRangeEvent(
                        id = item.optString("id").trim().takeIf(String::isNotBlank)
                            ?: "${localDate.orEmpty()}-${start.orEmpty()}-$title-$index",
                        title = title,
                        start = start,
                        end = end,
                        localDate = localDate,
                        localTime = localTime,
                        allDay = item.optBoolean("all_day", item.optBoolean("allDay", false)),
                        location = item.optString("location").trim().takeIf(String::isNotBlank),
                        description = item.optString("description").trim().takeIf(String::isNotBlank),
                        calendarId = item.optString("calendar_id").trim().takeIf(String::isNotBlank),
                        calendarName = item.optString("calendar_name").trim().takeIf(String::isNotBlank),
                        calendarColor = item.optString("calendar_color").trim().takeIf(String::isNotBlank),
                        calendarOwned = item.optBoolean("calendar_owned", true),
                    ),
                )
            }
        }
        val calendars = root.optJSONArray("calendars")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue
                    add(
                        CalendarSource(
                            id = id,
                            name = item.optString("name").trim().ifBlank { "Calendar" },
                            color = item.optString("color").trim().takeIf(String::isNotBlank),
                            owned = item.optBoolean("owned", false),
                            selected = item.optBoolean("selected", true),
                            primary = item.optBoolean("primary", false),
                        ),
                    )
                }
            }
        }.orEmpty()
        return CalendarRangeSnapshot(
            startDate = startDate,
            endDate = endDate,
            events = events,
            calendars = calendars,
            includesShared = root.optBoolean("includes_shared", false),
        )
    }
}

data class IntelligenceItem(
    val title: String,
    val source: String,
    val category: String,
    val link: String?,
    val publishedAtEpochMs: Long?,
)

data class IntelligenceSourceHealth(
    val source: String,
    val status: String,
    val items: Int?,
    val http: Int?,
    val error: String?,
)

data class IntelligenceSnapshot(
    val status: String,
    val updatedAtEpochMs: Long?,
    val sourceCount: Int?,
    val items: List<IntelligenceItem>,
    val sourceHealth: List<IntelligenceSourceHealth>,
    val sourceErrors: List<String>,
)

object IntelligencePayloadMapper {
    fun map(json: JSONObject): IntelligenceSnapshot {
        val root = unwrap(json)
        return IntelligenceSnapshot(
            status = root.optString("status", "unknown"),
            updatedAtEpochMs = parseInstant(root.optString("updated")),
            sourceCount = root.optInt("source_count").takeIf { root.has("source_count") },
            items = mapItems(root.optJSONArray("items")),
            sourceHealth = mapHealth(root.optJSONArray("source_health")),
            sourceErrors = mapErrors(root.optJSONArray("source_errors")),
        )
    }

    private fun mapItems(array: JSONArray?): List<IntelligenceItem> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                if (title.isBlank()) continue
                add(
                    IntelligenceItem(
                        title = title,
                        source = item.optString("source", "Unknown").trim().ifBlank { "Unknown" },
                        category = item.optString("category", "intelligence").trim().ifBlank { "intelligence" },
                        link = item.optString("link").trim().takeIf(String::isNotBlank),
                        publishedAtEpochMs = parseInstant(item.optString("published")),
                    ),
                )
            }
        }
    }

    private fun mapHealth(array: JSONArray?): List<IntelligenceSourceHealth> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    IntelligenceSourceHealth(
                        source = item.optString("source", "Unknown"),
                        status = item.optString("status", "unknown"),
                        items = item.optInt("items").takeIf { item.has("items") },
                        http = item.optInt("http").takeIf { item.has("http") },
                        error = item.optString("error").trim().takeIf(String::isNotBlank),
                    ),
                )
            }
        }
    }

    private fun mapErrors(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item != null) {
                    add(
                        listOfNotNull(
                            item.optString("source").trim().takeIf(String::isNotBlank),
                            item.optString("error").trim().takeIf(String::isNotBlank),
                        ).joinToString(": "),
                    )
                } else {
                    array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }
}

data class NutritionDay(
    val date: String,
    val calories: Double?,
    val protein: Double?,
    val carbs: Double?,
    val fat: Double?,
    val sodium: Double?,
    val mealCount: Int?,
)

data class NutritionMeal(
    val date: String?,
    val time: String?,
    val item: String,
    val portion: String?,
    val calories: Double?,
    val status: String?,
)

data class NutritionSnapshot(
    val days: Int,
    val today: NutritionDay?,
    val daily: List<NutritionDay>,
    val meals: List<NutritionMeal>,
    val calorieTarget: Double?,
    val proteinTarget: Double?,
    val pendingEstimateCount: Int,
)

object NutritionPayloadMapper {
    fun map(json: JSONObject, requestedDays: Int): NutritionSnapshot {
        val root = unwrap(json)
        val todayJson = root.optJSONObject("today")
        val daily = mapDays(root.optJSONArray("daily"))
        val meals = mapMeals(root.optJSONArray("meals"))
        val targets = root.optJSONObject("targets") ?: JSONObject()
        return NutritionSnapshot(
            days = root.optInt("days", requestedDays).coerceIn(1, 30),
            today = todayJson?.let { mapDay(it, it.optString("date")) },
            daily = daily,
            meals = meals,
            calorieTarget = firstNumber(targets, "calories", "calorie", "calorie_target"),
            proteinTarget = firstNumber(targets, "protein", "protein_target"),
            pendingEstimateCount = meals.count { it.status.equals("PENDING", ignoreCase = true) },
        )
    }

    private fun mapDays(array: JSONArray?): List<NutritionDay> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val date = item.optString("date").trim()
                if (date.isBlank()) continue
                add(mapDay(item, date))
            }
        }
    }

    private fun mapDay(item: JSONObject, date: String): NutritionDay = NutritionDay(
        date = date,
        calories = firstNumber(item, "calories", "total_calories"),
        protein = firstNumber(item, "protein", "protein_g", "total_protein"),
        carbs = firstNumber(item, "carbs", "carbs_g", "total_carbs"),
        fat = firstNumber(item, "fat", "fat_g", "total_fat"),
        sodium = firstNumber(item, "sodium", "sodium_mg"),
        mealCount = firstNumber(item, "meal_count")?.toInt(),
    )

    private fun mapMeals(array: JSONArray?): List<NutritionMeal> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("item").trim().ifBlank { "Meal" }
                add(
                    NutritionMeal(
                        date = item.optString("date").trim().takeIf(String::isNotBlank),
                        time = item.optString("time").trim().takeIf(String::isNotBlank),
                        item = name,
                        portion = item.optString("portion").trim().takeIf(String::isNotBlank),
                        calories = firstNumber(item, "calories", "total_calories"),
                        status = item.optString("status").trim().takeIf(String::isNotBlank)
                            ?: item.optString("source_status").trim().takeIf(String::isNotBlank),
                    ),
                )
            }
        }
    }
}

data class TaskHistoryItem(
    val id: String,
    val title: String,
    val completedAtEpochMs: Long?,
)

object TaskHistoryPayloadMapper {
    fun map(json: JSONObject): List<TaskHistoryItem> {
        val root = unwrap(json)
        val array = root.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val title = item.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue
                add(TaskHistoryItem(id, title, parseInstant(item.optString("completed"))))
            }
        }
    }
}

private fun unwrap(json: JSONObject): JSONObject = json.optJSONObject("data") ?: json

private fun firstNumber(json: JSONObject, vararg keys: String): Double? {
    keys.forEach { key ->
        if (json.has(key) && !json.isNull(key)) {
            val value = json.optDouble(key, Double.NaN)
            if (!value.isNaN()) return value
        }
    }
    return null
}

private fun parseInstant(value: String?): Long? = value
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
