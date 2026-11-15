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
    private var compatibleAppPackages: Set<String> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreference.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiInsets.apply(binding.root)

        val preferences = getSharedPreferences("app_settings", MODE_PRIVATE)
        binding.localStatsSwitch.isChecked = preferences.getBoolean("local_stats", true)
        binding.blocklistStatus.text = getString(R.string.blocklist_status, DomainBlocklist.ruleCount(this))

        when (ThemePreference.selected(this)) {
            ThemePreference.LIGHT -> binding.lightThemeOption.isChecked = true
            else -> binding.forestThemeOption.isChecked = true
        }
        binding.themeOptions.setOnCheckedChangeListener { _, checkedId ->
            val selected = if (checkedId == R.id.lightThemeOption) ThemePreference.LIGHT else ThemePreference.FOREST
            if (selected != ThemePreference.selected(this)) ThemePreference.select(this, selected)
        }

        val save = {
            preferences.edit {
                putBoolean("local_stats", binding.localStatsSwitch.isChecked)
            }
        }
        binding.localStatsSwitch.setOnCheckedChangeListener { _, _ -> save() }
        binding.excludeAllAppsButton.setOnClickListener {
            AppExclusionStore.replaceAll(this, compatibleAppPackages)
            showReconnectNoticeAndRefreshApps()
        }
        binding.clearExcludedAppsButton.setOnClickListener {
            AppExclusionStore.replaceAll(this, emptySet())
            showReconnectNoticeAndRefreshApps()
        }
        populateAppExclusions()
        binding.backButton.setOnClickListener { finish() }
    }

    private fun populateAppExclusions() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        compatibleAppPackages = apps.mapTo(mutableSetOf()) { it.activityInfo.packageName }
        val excluded = AppExclusionStore.packages(this)
        val horizontalPadding = (8 * resources.displayMetrics.density).toInt()

        binding.excludedAppsContainer.removeAllViews()
        apps.forEach { app ->
            val packageName = app.activityInfo.packageName
            binding.excludedAppsContainer.addView(SwitchMaterial(this).apply {
                text = app.loadLabel(packageManager)
                isChecked = packageName in excluded
                setTextColor(getColor(R.color.forest_paper))
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setPadding(horizontalPadding, 0, horizontalPadding, 0)
                setOnCheckedChangeListener { _, checked ->
                    AppExclusionStore.setExcluded(this@SettingsActivity, packageName, checked)
                    binding.reconnectNotice.visibility = View.VISIBLE
                }
            })
        }
    }

    private fun showReconnectNoticeAndRefreshApps() {
        binding.reconnectNotice.visibility = View.VISIBLE
        populateAppExclusions()
    }
}
