package com.simpleshield.adblocker

import android.content.Context

object AppExclusionStore {
    private const val PREFS = "app_exclusions"
    private const val KEY = "packages"

    fun packages(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()

    fun setExcluded(context: Context, packageName: String, excluded: Boolean) {
        val updated = packages(context).toMutableSet()
        if (excluded) updated.add(packageName) else updated.remove(packageName)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, updated).apply()
    }

    /** Replaces the exclusion set in one atomic preference update. */
    fun replaceAll(context: Context, packageNames: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, packageNames.toSet())
            .apply()
    }
}
