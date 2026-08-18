package com.simpleshield.adblocker

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.simpleshield.adblocker.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val preferences = getSharedPreferences("app_settings", MODE_PRIVATE)
        binding.startOnBootSwitch.isChecked = preferences.getBoolean("start_on_boot", true)
        binding.localStatsSwitch.isChecked = preferences.getBoolean("local_stats", true)

        val save = {
            preferences.edit {
                putBoolean("start_on_boot", binding.startOnBootSwitch.isChecked)
                putBoolean("local_stats", binding.localStatsSwitch.isChecked)
            }
        }
        listOf(binding.startOnBootSwitch, binding.localStatsSwitch)
            .forEach { toggle -> toggle.setOnCheckedChangeListener { _, _ -> save() } }
        populateAppExclusions()
        binding.backButton.setOnClickListener { finish() }
    }

    private fun populateAppExclusions() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        val excluded = AppExclusionStore.packages(this)

        binding.excludedAppsContainer.removeAllViews()
        apps.forEach { app ->
            val packageName = app.activityInfo.packageName
            binding.excludedAppsContainer.addView(SwitchMaterial(this).apply {
                text = app.loadLabel(packageManager)
                isChecked = packageName in excluded
                setTextColor(getColor(R.color.forest_paper))
                minHeight = (56 * resources.displayMetrics.density).toInt()
                setPadding(8, 0, 8, 0)
                setOnCheckedChangeListener { _, checked ->
                    AppExclusionStore.setExcluded(this@SettingsActivity, packageName, checked)
                    binding.reconnectNotice.visibility = View.VISIBLE
                }
            })
        }
    }
}
