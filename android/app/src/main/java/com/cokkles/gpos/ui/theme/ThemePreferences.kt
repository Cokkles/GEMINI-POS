package com.cokkles.gpos.ui.theme

import android.content.Context

class ThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): GposThemeOption =
        GposThemeOption.fromStorageKey(preferences.getString(KEY_THEME, null))

    fun save(option: GposThemeOption) {
        preferences.edit().putString(KEY_THEME, option.storageKey).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "gpos_ui_preferences"
        const val KEY_THEME = "theme"
    }
}
