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
    private const val SOURCES_VERSION_KEY = "sources_version"

    // Bump whenever `sources` changes so existing installs fetch the new
    // lists on the next start instead of waiting out the 24-hour interval.
    private const val SOURCES_VERSION = 3
    // Pop-up ad networks register new domains daily and the lists that track
    // them publish several times a day, so stale rules miss the newest ones.
    private const val UPDATE_INTERVAL_MS = 8 * 60 * 60 * 1000L
    private const val MINIMUM_VALID_RULES = 8_000
    private val sources = listOf(
        "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/data/StevenBlack/hosts",
        "https://raw.githubusercontent.com/bigdargon/hostsVN/master/option/hosts-VN",
        "https://raw.githubusercontent.com/tiuxo/hosts/master/ads",
        // Pop-up/redirect ad networks used by streaming and link sites. The
        // hosts-format lists above only name individual subdomains, which
        // these networks rotate constantly; these two list the registrable
        // domains, so matches() also covers every subdomain.
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt",
        "https://small.oisd.nl/domainswild2",
        // Broad ad/tracker coverage, maintained to avoid breaking sites.
        "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
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
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastUpdate = prefs.getLong(LAST_UPDATE, 0L)
        val sourcesUnchanged = prefs.getInt(SOURCES_VERSION_KEY, 1) == SOURCES_VERSION
        val updatedRecently = System.currentTimeMillis() - lastUpdate < UPDATE_INTERVAL_MS
        if ((sourcesUnchanged && updatedRecently) || !updating.compareAndSet(false, true)) return

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
                    .putLong(LAST_UPDATE, System.currentTimeMillis())
                    .putInt(SOURCES_VERSION_KEY, SOURCES_VERSION)
                    .apply()
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
            // Fast path for one-domain-per-line lists (and our own saved
            // file), which are hundreds of thousands of lines long.
            if (rawLine.isNotEmpty() && rawLine.none { it == '#' || it.isWhitespace() }) {
                val domain = rawLine.trimEnd('.').lowercase()
                if (isValidDomain(domain)) destination.add(domain)
                return@forEachLine
            }
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
