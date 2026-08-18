package com.simpleshield.adblocker

import android.content.Context
import java.text.DateFormat
import java.util.Date

object RecentBlocksStore {
    private const val PREFS = "recent_blocks"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 30

    data class Entry(val timestamp: Long, val category: String, val domain: String)

    @Synchronized fun record(context: Context, domain: String, category: String) {
        val entries = read(context).toMutableList()
        entries.add(0, Entry(System.currentTimeMillis(), category, domain))
        val encoded = entries.take(MAX_ENTRIES).joinToString("\n") { "${it.timestamp}|${it.category}|${it.domain}" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encoded).apply()
    }

    fun read(context: Context): List<Entry> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
            .lineSequence().mapNotNull { line ->
                val parts = line.split('|', limit = 3)
                if (parts.size == 3) parts[0].toLongOrNull()?.let { Entry(it, parts[1], parts[2]) } else null
            }.toList()

    fun displayText(context: Context): String = read(context).joinToString("\n") {
        "${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.timestamp))}  ${it.domain}"
    }.ifBlank { "No blocked domains yet" }
}
