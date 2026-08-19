package com.navplus.core.search

import com.navplus.core.common.model.LatLng
import com.navplus.core.search.model.SearchResult
import com.navplus.core.search.model.SearchResultType
import com.navplus.core.search.model.SearchSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searches locally against the OSM Photon index bundled with each downloaded region.
 * Falls back to empty results when no local index is available rather than failing.
 */
@Singleton
class OfflineSearchProvider @Inject constructor() : SearchProvider {

    override val name = "Offline (Photon)"
    override val isAvailable = true

    private var photonBaseUrl: String? = null

    fun configure(localPhotonUrl: String) {
        photonBaseUrl = localPhotonUrl
    }

    override suspend fun search(query: String, near: LatLng?, maxResults: Int): List<SearchResult> {
        val base = photonBaseUrl ?: return emptyList()
        return try {
            PhotonClient.search(base, query, near, maxResults)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun reverseGeocode(position: LatLng): SearchResult? {
        val base = photonBaseUrl ?: return null
        return try {
            PhotonClient.reverseGeocode(base, position)
        } catch (e: Exception) {
            null
        }
    }
}

private object PhotonClient {
    private val client = okhttp3.OkHttpClient()

    suspend fun search(base: String, query: String, near: LatLng?, max: Int): List<SearchResult> {
        val url = buildString {
            append("$base/api?q=")
            append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&limit=$max")
            if (near != null) append("&lat=${near.lat}&lon=${near.lng}")
        }
        val body = client.newCall(okhttp3.Request.Builder().url(url).build())
            .execute().body?.string() ?: return emptyList()
        return parsePhotonResponse(body)
    }

    suspend fun reverseGeocode(base: String, position: LatLng): SearchResult? {
        val url = "$base/reverse?lat=${position.lat}&lon=${position.lng}"
        val body = client.newCall(okhttp3.Request.Builder().url(url).build())
            .execute().body?.string() ?: return null
        return parsePhotonResponse(body).firstOrNull()
    }

    private fun parsePhotonResponse(json: String): List<SearchResult> {
        val obj = org.json.JSONObject(json)
        val features = obj.optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            try {
                val f = features.getJSONObject(i)
                val props = f.getJSONObject("properties")
                val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
                SearchResult(
                    id = "photon_$i",
                    title = props.optString("name").ifEmpty { props.optString("street") },
                    subtitle = listOfNotNull(
                        props.optString("city").takeIf { it.isNotEmpty() },
                        props.optString("country").takeIf { it.isNotEmpty() },
                    ).joinToString(", ").ifEmpty { null },
                    position = LatLng(coords.getDouble(1), coords.getDouble(0)),
                    type = SearchResultType.ADDRESS,
                    source = SearchSource.OFFLINE,
                )
            } catch (e: Exception) { null }
        }
    }
}
