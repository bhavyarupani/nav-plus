package com.navplus.core.search.model

import com.navplus.core.common.model.LatLng

data class SearchResult(
    val id: String,
    val title: String,
    val subtitle: String?,
    val position: LatLng,
    val type: SearchResultType,
    val source: SearchSource,
    val distance: Double? = null,
    val openNow: Boolean? = null,
    val phone: String? = null,
    val website: String? = null,
)

enum class SearchResultType {
    ADDRESS, CITY, POI, FUEL_STATION, SUPERMARKET, EV_CHARGER,
    PARKING, RESTAURANT, PHARMACY, CAMPSITE, REST_AREA,
}

enum class SearchSource { OFFLINE, ONLINE_NOMINATIM, ONLINE_TOMTOM }
