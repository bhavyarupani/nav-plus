package com.roadpulse.auto.quota

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GoogleUsageGuard(
    context: Context,
) {
    private val store = SharedPreferencesQuotaStore(context.applicationContext)
    private val monthProvider = {
        SimpleDateFormat("yyyy-MM", Locale.US)
            .apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
    }

    val searchRequests =
        MonthlyQuota(
            key = "google_places_search_requests",
            limit = SEARCH_REQUEST_LIMIT,
            store = store,
            periodProvider = monthProvider,
        )

    val navigationDestinations =
        MonthlyQuota(
            key = "google_navigation_destinations",
            limit = NAVIGATION_DESTINATION_LIMIT,
            store = store,
            periodProvider = monthProvider,
        )

    companion object {
        const val SEARCH_REQUEST_LIMIT = 1_000
        const val NAVIGATION_DESTINATION_LIMIT = 1_000
    }
}

private class SharedPreferencesQuotaStore(
    context: Context,
) : QuotaStore {
    private val preferences = context.getSharedPreferences("google_usage_guard", Context.MODE_PRIVATE)

    override fun readPeriod(key: String): String? = preferences.getString("${key}_period", null)

    override fun readUsed(key: String): Int = preferences.getInt("${key}_used", 0)

    override fun write(
        key: String,
        period: String,
        used: Int,
    ) {
        check(
            preferences
                .edit()
                .putString("${key}_period", period)
                .putInt("${key}_used", used)
                .commit(),
        ) { "Unable to persist API quota" }
    }
}
