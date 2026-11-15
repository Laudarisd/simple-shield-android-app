package com.simpleshield.adblocker

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.simpleshield.adblocker.databinding.ActivityFiltersBinding

class FiltersActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFiltersBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreference.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityFiltersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiInsets.apply(binding.root)

        val preferences = getSharedPreferences("protection_options", MODE_PRIVATE)
        binding.adsSwitch.isChecked = preferences.getBoolean("ads", true)

        val save = {
            preferences.edit {
                putBoolean("ads", binding.adsSwitch.isChecked)
            }
        }
        binding.adsSwitch.setOnCheckedChangeListener { _, _ -> save() }
        binding.addBlockButton.setOnClickListener {
            if (DomainRulesStore.addBlock(this, binding.blockDomainInput.text.toString())) {
                binding.blockDomainInput.text?.clear()
                refreshRules()
            } else Toast.makeText(this, R.string.invalid_domain, Toast.LENGTH_SHORT).show()
        }
        binding.addAllowButton.setOnClickListener {
            if (DomainRulesStore.addAllow(this, binding.allowDomainInput.text.toString())) {
                binding.allowDomainInput.text?.clear()
                refreshRules()
            } else Toast.makeText(this, R.string.invalid_domain, Toast.LENGTH_SHORT).show()
        }
        binding.clearBlockRulesButton.setOnClickListener { DomainRulesStore.clearBlock(this); refreshRules() }
        binding.clearAllowRulesButton.setOnClickListener { DomainRulesStore.clearAllow(this); refreshRules() }
        binding.backButton.setOnClickListener { finish() }
        refreshRules()
    }

    override fun onResume() {
        super.onResume()
        binding.recentBlocksText.text = RecentBlocksStore.displayText(this)
    }

    private fun refreshRules() {
        binding.blockRulesText.text = DomainRulesStore.blockDomains(this).sorted().joinToString("\n").ifBlank { getString(R.string.none_added) }
        binding.allowRulesText.text = DomainRulesStore.allowDomains(this).sorted().joinToString("\n").ifBlank { getString(R.string.none_added) }
        binding.recentBlocksText.text = RecentBlocksStore.displayText(this)
    }
}
