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

class PhotonOnlineSearchProvider(
    private val client: OkHttpClient,
) : SearchProvider {

    override val name = "Photon"
    override val isAvailable = true

    override suspend fun search(query: String, near: LatLng?, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = buildString {
                    append("https://photon.komoot.io/api?q=$encoded&limit=$maxResults")
                    if (near != null) append("&lat=${near.lat}&lon=${near.lng}")
                }
                val body = client.newCall(Request.Builder().url(url).build())
                    .execute().body?.string() ?: return@withContext emptyList()
                parsePhoton(body)
            } catch (e: Exception) {
                emptyList()
            }
        }

    override suspend fun reverseGeocode(position: LatLng): SearchResult? =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://photon.komoot.io/reverse?lat=${position.lat}&lon=${position.lng}"
                val body = client.newCall(Request.Builder().url(url).build())
                    .execute().body?.string() ?: return@withContext null
                parsePhoton(body).firstOrNull()
            } catch (e: Exception) {
                null
            }
        }

    private fun parsePhoton(json: String): List<SearchResult> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            try {
                val f = features.getJSONObject(i)
                val props = f.getJSONObject("properties")
                val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
                SearchResult(
                    id = "photon_online_${UUID.randomUUID()}",
                    title = props.optString("name").ifEmpty { props.optString("street") },
                    subtitle = listOfNotNull(
                        props.optString("city").takeIf { it.isNotEmpty() },
                        props.optString("country").takeIf { it.isNotEmpty() },
                    ).joinToString(", ").ifEmpty { null },
                    position = LatLng(coords.getDouble(1), coords.getDouble(0)),
                    type = SearchResultType.ADDRESS,
                    source = SearchSource.ONLINE_NOMINATIM,
                )
            } catch (e: Exception) { null }
        }
    }
}
