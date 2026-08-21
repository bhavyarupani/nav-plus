package com.navplus.core.search

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.distanceTo
import com.navplus.core.connectivity.ConnectivityState
import com.navplus.core.connectivity.NetworkConnectivityManager
import com.navplus.core.search.model.SearchResult
import com.navplus.core.search.model.SearchSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val connectivity: NetworkConnectivityManager,
    private val offlineProvider: OfflineSearchProvider,
    private val onlineProviders: Set<@JvmSuppressWildcards SearchProvider>,
) {
    suspend fun search(query: String, near: LatLng?): List<SearchResult> = coroutineScope {
        val offlineDeferred = async { offlineProvider.search(query, near) }

        val onlineDeferred = if (connectivity.state.value.isOnline) {
            async {
                onlineProviders
                    .filter { it.isAvailable }
                    .flatMap { it.search(query, near, maxResults = 5) }
            }
        } else null

        val offline = offlineDeferred.await()
        val online = onlineDeferred?.await() ?: emptyList()

        merge(offline, online, near)
    }

    suspend fun reverseGeocode(position: LatLng): SearchResult? {
        val offline = offlineProvider.reverseGeocode(position)
        if (offline != null) return offline
        if (!connectivity.state.value.isOnline) return null
        return onlineProviders.firstOrNull { it.isAvailable }?.reverseGeocode(position)
    }

    private fun merge(offline: List<SearchResult>, online: List<SearchResult>, near: LatLng?): List<SearchResult> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<SearchResult>()

        for (r in offline) {
            val ranked = r.withDistanceFrom(near)
            if (seen.add(normaliseKey(ranked))) result.add(ranked)
        }
        for (r in online) {
            val ranked = r.withDistanceFrom(near)
            if (seen.add(normaliseKey(ranked))) result.add(ranked)
        }
        return result.sortedBy { it.distance ?: Double.MAX_VALUE }
    }

    private fun SearchResult.withDistanceFrom(near: LatLng?): SearchResult {
        if (near == null || distance != null) return this
        return copy(distance = near.distanceTo(position))
    }

    private fun normaliseKey(r: SearchResult) =
        "${r.title.lowercase().trim()}|${"%.4f".format(r.position.lat)}|${"%.4f".format(r.position.lng)}"
}
