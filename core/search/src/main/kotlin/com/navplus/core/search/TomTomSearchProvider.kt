package com.navplus.core.search

import com.navplus.core.common.model.LatLng
import com.navplus.core.search.model.SearchResult
import com.navplus.core.search.model.SearchResultType
import com.navplus.core.search.model.SearchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

class TomTomSearchProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
) : SearchProvider {

    override val name = "TomTom"

    @Volatile private var quotaResetAt = 0L

    override val isAvailable: Boolean
        get() = apiKey.isNotEmpty() && System.currentTimeMillis() >= quotaResetAt

    override suspend fun search(query: String, near: LatLng?, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = buildString {
                    append("https://api.tomtom.com/search/2/search/$encoded.json")
                    append("?key=$apiKey&limit=$maxResults&typeahead=true")
                    if (near != null) append("&lat=${near.lat}&lon=${near.lng}&radius=50000")
                }
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (response.code == 429) { onQuotaExceeded(); return@withContext emptyList() }
                if (!response.isSuccessful) return@withContext emptyList()
                parseTomTomSearch(response.body?.string() ?: return@withContext emptyList())
            } catch (e: Exception) {
                emptyList()
            }
        }

    override suspend fun reverseGeocode(position: LatLng): SearchResult? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.tomtom.com/search/2/reverseGeocode/${position.lat},${position.lng}.json?key=$apiKey"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (response.code == 429) { onQuotaExceeded(); return@withContext null }
                if (!response.isSuccessful) return@withContext null
                parseTomTomSearch(response.body?.string() ?: return@withContext null).firstOrNull()
            } catch (e: Exception) {
                null
            }
        }

    private fun onQuotaExceeded() {
        quotaResetAt = System.currentTimeMillis() + 60 * 60 * 1000L // back off 1 hour
    }

    private fun parseTomTomSearch(json: String): List<SearchResult> {
        val root = JSONObject(json)
        val results = root.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            try {
                val r = results.getJSONObject(i)
                val pos = r.getJSONObject("position")
                val address = r.optJSONObject("address")
                val poi = r.optJSONObject("poi")
                SearchResult(
                    id = "tt_${UUID.randomUUID()}",
                    title = poi?.optString("name")?.takeIf { it.isNotEmpty() }
                        ?: address?.optString("freeformAddress") ?: return@mapNotNull null,
                    subtitle = address?.optString("freeformAddress")?.takeIf { it.isNotEmpty() },
                    position = LatLng(pos.getDouble("lat"), pos.getDouble("lon")),
                    type = if (poi != null) SearchResultType.POI else SearchResultType.ADDRESS,
                    source = SearchSource.ONLINE_TOMTOM,
                )
            } catch (e: Exception) { null }
        }
    }
}
