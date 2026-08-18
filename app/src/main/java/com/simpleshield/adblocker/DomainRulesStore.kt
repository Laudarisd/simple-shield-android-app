package com.simpleshield.adblocker

import android.content.Context

/** User-maintained rules. Allow rules always win over built-in/custom blocks. */
object DomainRulesStore {
    private const val PREFS = "domain_rules"
    private const val ALLOW = "allow_domains"
    private const val BLOCK = "block_domains"

    fun normalize(value: String): String? {
        val domain = value.trim().trimEnd('.').lowercase()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/').substringBefore(':')
        return domain.takeIf {
            it.isNotBlank() && it.length <= 253 &&
                it.split('.').all { label -> label.isNotBlank() && label.length <= 63 && label.all { ch -> ch.isLetterOrDigit() || ch == '-' } }
        }
    }

    fun allowDomains(context: Context): Set<String> = values(context, ALLOW)
    fun blockDomains(context: Context): Set<String> = values(context, BLOCK)

    fun addAllow(context: Context, raw: String): Boolean = add(context, ALLOW, raw)
    fun addBlock(context: Context, raw: String): Boolean = add(context, BLOCK, raw)
    fun clearAllow(context: Context) = clear(context, ALLOW)
    fun clearBlock(context: Context) = clear(context, BLOCK)

    fun matches(domain: String, rules: Set<String>): Boolean {
        val normalized = domain.trimEnd('.').lowercase()
        return rules.any { normalized == it || normalized.endsWith(".$it") }
    }

    private fun values(context: Context, key: String): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key, emptySet()).orEmpty()

    private fun add(context: Context, key: String, raw: String): Boolean {
        val domain = normalize(raw) ?: return false
        val updated = values(context, key).toMutableSet().apply { add(domain) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(key, updated).apply()
        return true
    }

    private fun clear(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(key).apply()
    }
}
