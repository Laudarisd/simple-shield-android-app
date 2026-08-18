package com.simpleshield.adblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

class ResumeProtectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESUME) return
        if (VpnService.prepare(context) != null) return
        ContextCompat.startForegroundService(context, Intent(context, AdBlockVpnService::class.java))
    }

    companion object { const val ACTION_RESUME = "com.simpleshield.adblocker.RESUME_PROTECTION" }
}
