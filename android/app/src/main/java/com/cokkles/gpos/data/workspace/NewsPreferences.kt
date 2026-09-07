package com.cokkles.gpos.data.workspace

import android.content.Context
import com.cokkles.gpos.data.remote.IntelligenceItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object HeadlinerPolicy {
    fun key(category: String): String = category.trim().lowercase().replace(Regex("[_\\s-]+"), " ")
    fun eligible(category: String, overrides: Map<String, Boolean>): Boolean =
        overrides[key(category)] ?: !key(category).split(Regex("[^a-z]+")).any { it == "deal" || it == "deals" }
    fun select(items: List<IntelligenceItem>, overrides: Map<String, Boolean>, limit: Int): List<IntelligenceItem> =
        items.filter { eligible(it.category, overrides) }.distinctBy { it.link ?: it.title }.take(limit)
}

object NewsSectionOrder {
    fun arrange(categories: Collection<String>, savedKeys: List<String>): List<String> {
        val byKey = categories.associateBy(HeadlinerPolicy::key)
        return (savedKeys.mapNotNull(byKey::get) + categories.filterNot { HeadlinerPolicy.key(it) in savedKeys }).distinct()
    }

    fun move(categories: Collection<String>, savedKeys: List<String>, category: String, direction: Int): List<String> {
        val arranged = arrange(categories, savedKeys).map(HeadlinerPolicy::key).toMutableList()
        val from = arranged.indexOf(HeadlinerPolicy.key(category))
        val to = (from + direction).coerceIn(0, arranged.lastIndex)
        if (from >= 0 && from != to) {
            val item = arranged.removeAt(from)
            arranged.add(to, item)
        }
        return arranged
    }
}
class NewsPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aegis_headliners", Context.MODE_PRIVATE)
    private val _overrides = MutableStateFlow(prefs.all.mapNotNull { (k,v) -> (v as? Boolean)?.let { k to it } }.toMap())
    val overrides = _overrides.asStateFlow()
    private val _sectionOrder = MutableStateFlow(prefs.getString(KEY_ORDER, "").orEmpty().split('|').filter(String::isNotBlank))
    val sectionOrder = _sectionOrder.asStateFlow()
    fun set(category: String, allowed: Boolean) {
        val key = HeadlinerPolicy.key(category)
        prefs.edit().putBoolean(key, allowed).apply()
        _overrides.value = _overrides.value + (key to allowed)
    }
    fun move(category: String, direction: Int, categories: Collection<String>) {
        val next = NewsSectionOrder.move(categories, _sectionOrder.value, category, direction)
        prefs.edit().putString(KEY_ORDER, next.joinToString("|")).apply()
        _sectionOrder.value = next
    }

    private companion object { const val KEY_ORDER = "section_order" }
}
