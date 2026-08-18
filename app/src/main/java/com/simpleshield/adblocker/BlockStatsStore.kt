package com.simpleshield.adblocker

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists today's block counts per category so the numbers on MainActivity
 * survive the Activity being recreated (rotation, reopening the app) instead
 * of resetting to 0, and roll over automatically at the start of a new day
 * to match the "blocked today" label.
 */
object BlockStatsStore {
    private const val PREFS_NAME = "block_stats"
    private const val KEY_DATE = "date"
    private const val KEY_ADS_COUNT = "ads_count"
    private const val KEY_SPONSORED_COUNT = "sponsored_count"

    data class Counts(val ads: Int, val sponsored: Int)

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun readTodayCounts(context: Context): Counts {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_DATE, null) != today()) return Counts(0, 0)
        return Counts(prefs.getInt(KEY_ADS_COUNT, 0), prefs.getInt(KEY_SPONSORED_COUNT, 0))
    }

    /** Only ever called from the VPN service's single packet-handling
     * thread, so a plain read-modify-write is safe here. */
    fun recordBlock(context: Context, category: BlockedDomainList.Category): Counts {
        val current = readTodayCounts(context)
        val updated = when (category) {
            BlockedDomainList.Category.ADS -> current.copy(ads = current.ads + 1)
            BlockedDomainList.Category.SPONSORED -> current.copy(sponsored = current.sponsored + 1)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DATE, today())
            .putInt(KEY_ADS_COUNT, updated.ads)
            .putInt(KEY_SPONSORED_COUNT, updated.sponsored)
            .apply()
        return updated
    }
}
