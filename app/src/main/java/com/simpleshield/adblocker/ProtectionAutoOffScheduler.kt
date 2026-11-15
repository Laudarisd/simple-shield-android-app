package com.simpleshield.adblocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Schedules protection to turn itself off exactly once per activation, a
 * fixed duration after it starts, by firing the same ACTION_STOP_SERVICE
 * intent used by the notification's Stop action.
 */
object ProtectionAutoOffScheduler {
    /** One explicit activation protects the device for a maximum of 24 hours. */
    const val AUTO_OFF_DURATION_MS = 24 * 60 * 60 * 1000L
    private const val REQUEST_CODE = 42

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAtElapsed = SystemClock.elapsedRealtime() + AUTO_OFF_DURATION_MS
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtElapsed,
            pendingIntent(context),
        )
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_CODE,
        Intent(context, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP_SERVICE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
