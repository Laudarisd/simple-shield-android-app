package com.simpleshield.adblocker

import android.content.Context

/** Small shared source of truth for the VPN's actual established state. */
object ProtectionStateStore {
    private const val PREFS_NAME = "protection_state"
    private const val KEY_ACTIVE = "active"

    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)

    fun setActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACTIVE, active)
            .apply()
    }
}
