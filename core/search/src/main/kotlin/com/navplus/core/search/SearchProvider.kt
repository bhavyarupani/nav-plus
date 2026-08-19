package com.navplus.core.search

import com.navplus.core.common.model.LatLng
import com.navplus.core.search.model.SearchResult

interface SearchProvider {
    val name: String
    val isAvailable: Boolean

    suspend fun search(query: String, near: LatLng?, maxResults: Int = 10): List<SearchResult>
    suspend fun reverseGeocode(position: LatLng): SearchResult?
}
