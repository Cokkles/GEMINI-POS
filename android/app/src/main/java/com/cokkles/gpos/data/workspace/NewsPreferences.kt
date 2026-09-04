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
class NewsPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aegis_headliners", Context.MODE_PRIVATE)
    private val _overrides = MutableStateFlow(prefs.all.mapNotNull { (k,v) -> (v as? Boolean)?.let { k to it } }.toMap())
    val overrides = _overrides.asStateFlow()
    fun set(category: String, allowed: Boolean) {
        val key = HeadlinerPolicy.key(category)
        prefs.edit().putBoolean(key, allowed).apply()
        _overrides.value = _overrides.value + (key to allowed)
    }
}
