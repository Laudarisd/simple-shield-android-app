package com.simpleshield.adblocker

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Bundled offline rules plus a validated, atomic HTTPS update path. */
object DomainBlocklist {
    private const val ASSET_NAME = "production_domains.txt"
    private const val UPDATE_FILE = "production_domains.txt"
    private const val PREFS = "blocklist"
    private const val LAST_UPDATE = "last_update"
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val MINIMUM_VALID_RULES = 8_000
    private val sources = listOf(
        "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/data/StevenBlack/hosts",
        "https://raw.githubusercontent.com/bigdargon/hostsVN/master/option/hosts-VN",
        "https://raw.githubusercontent.com/tiuxo/hosts/master/ads",
    )
    private val updating = AtomicBoolean(false)
    private val updateExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SimpleShield-RuleUpdate").apply { isDaemon = true }
    }

    @Volatile private var domains: Set<String>? = null

    fun load(context: Context) {
        if (domains != null) return
        synchronized(this) {
            if (domains == null) {
                val updatedFile = context.getFileStreamPath(UPDATE_FILE)
                val loaded = if (updatedFile.isFile) updatedFile.bufferedReader().use(::parse) else emptySet()
                domains = loaded.takeIf { it.size >= MINIMUM_VALID_RULES }
                    ?: context.assets.open(ASSET_NAME).bufferedReader().use(::parse)
            }
        }
    }

    fun ruleCount(context: Context): Int {
        load(context)
        return domains.orEmpty().size
    }

    fun updateIfDue(context: Context) {
        val appContext = context.applicationContext
        val lastUpdate = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - lastUpdate < UPDATE_INTERVAL_MS || !updating.compareAndSet(false, true)) return

        updateExecutor.execute {
            try {
                val refreshed = HashSet<String>()
                sources.forEach { source ->
                    runCatching { downloadInto(source, refreshed) }
                        .onFailure { Log.w("SimpleShieldRules", "Could not refresh $source", it) }
                }
                val currentSize = domains.orEmpty().size
                check(refreshed.size >= maxOf(MINIMUM_VALID_RULES, currentSize * 7 / 10)) {
                    "Rule update was unexpectedly small"
                }

                val temporary = appContext.getFileStreamPath("$UPDATE_FILE.tmp")
                temporary.bufferedWriter().use { writer ->
                    refreshed.sorted().forEach { domain -> writer.appendLine(domain) }
                }
                check(temporary.renameTo(appContext.getFileStreamPath(UPDATE_FILE))) { "Could not install rule update" }
                domains = refreshed
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(LAST_UPDATE, System.currentTimeMillis()).apply()
                Log.i("SimpleShieldRules", "Installed ${refreshed.size} current blocking rules")
            } catch (error: Exception) {
                Log.w("SimpleShieldRules", "Rule refresh failed; keeping existing offline rules", error)
            } finally {
                updating.set(false)
            }
        }
    }

    fun matches(context: Context, queriedDomain: String): Boolean {
        load(context)
        val rules = checkNotNull(domains)
        var candidate = queriedDomain.trimEnd('.').lowercase()
        while (true) {
            if (candidate in rules) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun downloadInto(source: String, destination: MutableSet<String>) {
        val connection = URL(source).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "SimpleShield-Android/1.0")
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { parseInto(it, destination) }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(reader: java.io.BufferedReader): Set<String> = HashSet<String>().also { parseInto(reader, it) }

    private fun parseInto(reader: java.io.BufferedReader, destination: MutableSet<String>) {
        reader.forEachLine { rawLine ->
            val fields = rawLine.substringBefore('#').trim().split(Regex("\\s+")).filter(String::isNotEmpty)
            val candidate = when {
                fields.size >= 2 && (fields[0] == "0.0.0.0" || fields[0] == "127.0.0.1") -> fields[1]
                fields.size == 1 -> fields[0]
                else -> return@forEachLine
            }.trimEnd('.').lowercase()
            if (isValidDomain(candidate)) destination.add(candidate)
        }
    }

    private fun isValidDomain(domain: String): Boolean =
        domain.length in 3..253 && domain.contains('.') && domain.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() && label.all { it.isLetterOrDigit() || it == '-' }
        }
}
