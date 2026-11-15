package com.simpleshield.adblocker

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemePreference {
    const val FOREST = "forest"
    const val LIGHT = "light"
    private const val KEY = "theme"

    fun selected(context: Context): String = context
        .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        .getString(KEY, FOREST) ?: FOREST

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            if (selected(context) == LIGHT) AppCompatDelegate.MODE_NIGHT_NO
            else AppCompatDelegate.MODE_NIGHT_YES,
        )
    }

    fun select(context: Context, value: String) {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
        apply(context)
    }
}
