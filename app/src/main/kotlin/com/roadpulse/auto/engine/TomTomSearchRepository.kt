package com.roadpulse.auto.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.roadpulse.auto.traffic.RoadCoordinate
import com.roadpulse.auto.util.manifestMetadataString
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Online supplement to [OfflineSearchEngine] via TomTom's free Fuzzy Search API
 * (api.tomtom.com/search/2/search) - same free, no-billing-account tier as
 * [com.roadpulse.auto.traffic.TomTomTrafficRepository] (2,500 non-tile requests/day, no card
 * required). Returns brand/POI results the OSM-derived offline index may miss or have stale, but
 * never replaces it: the offline index is what makes search work with no signal at all, which
 * this deliberately cannot do. Callers should merge, not switch - see `MainActivity`'s search
 * dialog for the merge-and-dedupe point, which also debounces this specifically (unlike the
 * instant offline query) since it's the one search path that costs a network request.
 *
 * No unified place model (RSPlace) exists yet to merge duplicate results with real confidence
 * scoring - see SEARCH_ARCHITECTURE.md. This class returns TomTom's results as plain
 * [SearchResult]s; the caller does a coarse distance+name dedupe against the offline results,
 * not true conflation.
 */
class TomTomSearchRepository(
    // Nullable so tests can pass null directly (see TomTomSearchRepositoryTest) - a distinct
    // non-null Context overload isn't possible here without an unused second parameter, since
    // Context and Context? erase to the same JVM constructor signature.
    private val appContext: Context?,
) {
    fun search(
        query: String,
        nearCoordinate: RoadCoordinate?,
    ): List<SearchResult> {
        val context = appContext ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val apiKey = manifestMetadataString(context, "com.roadpulse.auto.TOMTOM_API_KEY")
        if (apiKey.isBlank() || apiKey == "DEFAULT_API_KEY") return emptyList()
        if (!hasValidatedInternet(context)) return emptyList()
        return runCatching { parse(download(query, nearCoordinate, apiKey)) }.getOrDefault(emptyList())
    }

    internal fun parse(json: String): List<SearchResult> {
        val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until results.length()) {
                val entry = results.optJSONObject(index) ?: continue
                val position = entry.optJSONObject("position") ?: continue
                val lat = position.optDouble("lat")
                val lon = position.optDouble("lon")
                if (lat.isNaN() || lon.isNaN()) continue
                val poi = entry.optJSONObject("poi")
                val address = entry.optJSONObject("address")
                val title =
                    poi?.optString("name")?.takeIf(String::isNotBlank)
                        ?: address?.optString("freeformAddress")?.takeIf(String::isNotBlank)
                        ?: continue
                add(
                    SearchResult(
                        title = title,
                        subtitle = subtitleFor(poi, address),
                        coordinate = RoadCoordinate(lat, lon),
                    ),
                )
            }
        }
    }

    /** Packs whichever of address/category/phone TomTom actually returned into one line - there's
     * no richer POI-details UI yet for these fields to go into individually (SEARCH_ARCHITECTURE.md). */
    private fun subtitleFor(
        poi: JSONObject?,
        address: JSONObject?,
    ): String? {
        val freeform = address?.optString("freeformAddress")?.trim()?.takeIf(String::isNotBlank)
        val category =
            poi
                ?.optJSONArray("categories")
                ?.let { categories -> if (categories.length() > 0) categories.optString(0) else null }
                ?.replace('_', ' ')
                ?.takeIf(String::isNotBlank)
        return freeform ?: category
    }

    private fun download(
        query: String,
        nearCoordinate: RoadCoordinate?,
        apiKey: String,
    ): String {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val bias =
            nearCoordinate
                ?.let {
                    "&lat=${it.latitude}&lon=${it.longitude}"
                }.orEmpty()
        val url =
            "$BASE_URL/$encodedQuery.json?key=${URLEncoder.encode(apiKey, Charsets.UTF_8.name())}" +
                "&limit=$RESULT_LIMIT&language=en-GB$bias"
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", "RoadPulse/0.1 personal Android navigation prototype")
            if (connection.responseCode !in 200..299) {
                error("TomTom search query returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun hasValidatedInternet(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val BASE_URL = "https://api.tomtom.com/search/2/search"
        const val RESULT_LIMIT = 10
        const val CONNECT_TIMEOUT_MILLIS = 8_000
        const val READ_TIMEOUT_MILLIS = 12_000
    }
}
