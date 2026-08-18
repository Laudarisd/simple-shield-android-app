package com.simpleshield.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

/**
 * Makes the Settings screen's "Start protection after restart" toggle
 * actually do something. Without this receiver, that switch saved a
 * preference nobody ever read, and protection stayed off after every
 * reboot until the user manually reopened the app.
 *
 * A BroadcastReceiver can't show the system VPN permission dialog (no UI
 * context for it), so this only starts blocking when VpnService.prepare()
 * reports permission was already granted in an earlier session.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val startOnBoot = context
            .getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .getBoolean("start_on_boot", true)
        if (!startOnBoot) return

        if (VpnService.prepare(context) != null) return // permission not already granted

        ContextCompat.startForegroundService(
            context,
            Intent(context, AdBlockVpnService::class.java),
        )
    }
}
