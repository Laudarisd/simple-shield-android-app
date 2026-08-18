package com.simpleshield.adblocker

import android.Manifest
import android.content.res.ColorStateList
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.simpleshield.adblocker.databinding.ActivityMainBinding

/**
 * MainActivity.kt
 *
 * The single screen of this app: a big on/off switch for ad blocking, plus
 * a running count of how many ad/tracker lookups have been blocked.
 *
 * Turning blocking on requires the user's one-time permission for this app
 * to act as a local VPN (that's how AdBlockVpnService is able to intercept
 * DNS lookups — see the comment at the top of that file for what it does
 * and doesn't have access to). Android shows its own system permission
 * dialog for this; we don't (and can't) skip or customize it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private var isBlockingOn = false

    /** Handles the result of Android's "Allow this app to set up a VPN
     * connection?" system dialog, triggered by requestVpnPermissionThenStart(). */
    private val vpnPermissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startAdBlockingService()
        } else {
            updateToggleUiState(isBlockingOn = false)
        }
    }

    /** Android 13+ requires explicit runtime permission to show ANY
     * notification, including the persistent "blocking is active" one our
     * foreground service displays. We ask for it, but proceed either way —
     * blocking still works even if the user declines, it just won't show
     * the status notification. */
    private val notificationPermissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        requestVpnPermissionThenStart()
    }

    /** Listens for AdBlockVpnService.ACTION_DOMAIN_BLOCKED broadcasts so the
     * on-screen counters update live while blocking is active. The service
     * already persists the updated counts before sending this broadcast, so
     * re-reading from BlockStatsStore keeps the UI and the stored totals
     * (which survive rotation/reopening the app) always in sync. */
    private val domainBlockedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                AdBlockVpnService.ACTION_DOMAIN_BLOCKED -> refreshBlockedCounts()
                AdBlockVpnService.ACTION_SERVICE_STATE_CHANGED -> updateToggleUiState(
                    intent.getBooleanExtra(AdBlockVpnService.EXTRA_SERVICE_ACTIVE, false),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        viewBinding.blockingToggleButton.setOnClickListener {
            if (!isBlockingOn) {
                requestNotificationPermissionIfNeededThenStart()
            } else {
                stopAdBlockingService()
            }
        }
        viewBinding.pauseFiveButton.setOnClickListener { pauseProtection(5) }
        viewBinding.pauseFifteenButton.setOnClickListener { pauseProtection(15) }

        viewBinding.protectionHalo.setOnClickListener {
            viewBinding.blockingToggleButton.performClick()
        }
        viewBinding.filtersButton.setOnClickListener {
            startActivity(Intent(this, FiltersActivity::class.java))
        }
        viewBinding.settingsLargeButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateToggleUiState(isBlockingOn = AdBlockVpnService.isRunningInProcess())
        refreshBlockedCounts()
    }

    override fun onStart() {
        super.onStart()
        updateToggleUiState(isBlockingOn = AdBlockVpnService.isRunningInProcess())
        refreshBlockedCounts()
        val filter = IntentFilter().apply {
            addAction(AdBlockVpnService.ACTION_DOMAIN_BLOCKED)
            addAction(AdBlockVpnService.ACTION_SERVICE_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(domainBlockedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(domainBlockedReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(domainBlockedReceiver)
    }

    /** Checks/requests POST_NOTIFICATIONS on Android 13+ before proceeding
     * to the VPN permission step; on older Android versions this permission
     * doesn't exist, so we skip straight to requestVpnPermissionThenStart(). */
    private fun requestNotificationPermissionIfNeededThenStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            requestVpnPermissionThenStart()
            return
        }
        val alreadyGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

        if (alreadyGranted) {
            requestVpnPermissionThenStart()
        } else {
            notificationPermissionRequestLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Android requires explicit, per-install user permission before any app
     * can act as a VPN. VpnService.prepare() returns null if permission was
     * already granted (so we can start immediately); otherwise it returns
     * an Intent for the system's permission screen, which we launch and
     * handle the result of above in vpnPermissionRequestLauncher.
     */
    private fun requestVpnPermissionThenStart() {
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            vpnPermissionRequestLauncher.launch(permissionIntent)
        } else {
            startAdBlockingService()
        }
    }

    private fun startAdBlockingService() {
        val serviceIntent = Intent(this, AdBlockVpnService::class.java)
        ProtectionPauseScheduler.cancel(this)
        ProtectionStateStore.setActive(this, true)
        ContextCompat.startForegroundService(this, serviceIntent)
        updateToggleUiState(isBlockingOn = true)
    }

    private fun stopAdBlockingService() {
        // An explicit stop means stay stopped, including after reboot.
        getSharedPreferences("app_settings", MODE_PRIVATE)
            .edit()
            .putBoolean("start_on_boot", false)
            .apply()
        // Send an explicit stop command so the service closes the VPN
        // interface and foreground notification before terminating.
        startService(
            Intent(this, AdBlockVpnService::class.java)
                .setAction(AdBlockVpnService.ACTION_STOP_SERVICE),
        )
        ProtectionStateStore.setActive(this, false)
        updateToggleUiState(isBlockingOn = false)
    }

    private fun pauseProtection(minutes: Int) {
        if (!isBlockingOn) return
        ProtectionPauseScheduler.schedule(this, minutes)
        startService(
            Intent(this, AdBlockVpnService::class.java)
                .setAction(AdBlockVpnService.ACTION_PAUSE_SERVICE),
        )
        ProtectionStateStore.setActive(this, false)
        updateToggleUiState(isBlockingOn = false)
    }

    private fun refreshBlockedCounts() {
        val counts = BlockStatsStore.readTodayCounts(this)
        viewBinding.adsBlockedCountText.text = counts.ads.toString()
        viewBinding.sponsoredBlockedCountText.text = counts.sponsored.toString()
    }

    private fun updateToggleUiState(isBlockingOn: Boolean) {
        this.isBlockingOn = isBlockingOn
        viewBinding.statusText.setText(if (isBlockingOn) R.string.status_protecting_short else R.string.status_stopped_short)
        viewBinding.statusDetailText.setText(if (isBlockingOn) R.string.status_protecting_detail else R.string.status_stopped_detail)
        viewBinding.blockingToggleButton.setText(if (isBlockingOn) R.string.stop_protection_button else R.string.start_protection)
        viewBinding.blockingToggleButton.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isBlockingOn) R.color.stop_red else R.color.forest_accent),
        )
        viewBinding.protectionHalo.setBackgroundResource(if (isBlockingOn) R.drawable.bg_protection_halo else R.drawable.bg_protection_halo_off)
        viewBinding.pauseActions.visibility = if (isBlockingOn) android.view.View.VISIBLE else android.view.View.GONE
    }
}
