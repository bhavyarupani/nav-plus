package com.navplus.core.regions

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.distanceTo
import javax.inject.Inject
import javax.inject.Singleton

data class BorderCrossing(
    val distanceMeters: Double,
    val fromCountry: CountryInfo,
    val toCountry: CountryInfo,
    val position: LatLng,
)

data class CountryInfo(
    val code: String,
    val name: String,
    val flag: String,
    val maxSpeedKph: Int,
    val motorwaySpeedKph: Int,
    val requiresVignette: Boolean,
    val vignetteNote: String? = null,
    val currencyCode: String,
    val fuelNote: String? = null,
)

@Singleton
class BorderCrossingDetector @Inject constructor() {

    fun detectCrossings(route: Route, currentDistanceMeters: Double): List<BorderCrossing> {
        val crossings = mutableListOf<BorderCrossing>()
        val geom = route.geometry
        var accumulated = 0.0
        var lastCountry = countryForPoint(geom.firstOrNull() ?: return emptyList())

        for (i in 1 until geom.size) {
            val seg = geom[i - 1].distanceTo(geom[i])
            accumulated += seg
            if (accumulated < currentDistanceMeters) continue

            val country = countryForPoint(geom[i])
            if (country != null && country.code != (lastCountry?.code ?: country.code)) {
                val from = lastCountry ?: continue
                crossings.add(BorderCrossing(
                    distanceMeters = accumulated - currentDistanceMeters,
                    fromCountry = from,
                    toCountry = country,
                    position = geom[i],
                ))
                lastCountry = country
            } else if (country != null) {
                lastCountry = country
            }
        }
        return crossings
    }

    // Bounding-box country lookup for EU/EEA countries
    private fun countryForPoint(point: LatLng): CountryInfo? {
        return COUNTRY_BOXES.firstOrNull { (box, _) ->
            point.lat in box.minLat..box.maxLat && point.lng in box.minLng..box.maxLng
        }?.second
    }

    private data class BBox(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double)
    private val COUNTRY_BOXES: List<Pair<BBox, CountryInfo>> = listOf(
        BBox(47.2, 55.1, 5.8, 15.1) to CountryInfo("DE", "Germany",     "🇩🇪", 100, 130, false, null,           "EUR"),
        BBox(46.3, 49.0, 9.5, 17.2) to CountryInfo("AT", "Austria",     "🇦🇹", 100, 130, true,  "Motorway vignette required", "EUR", "Fuel cheaper before border"),
        BBox(47.0, 52.0, 5.9, 10.5) to CountryInfo("CH", "Switzerland", "🇨🇭", 80,  120, true,  "Annual vignette (CHF 40)",   "CHF", "Expensive fuel — fill up before"),
        BBox(47.7, 51.1, 2.5, 8.3)  to CountryInfo("FR", "France",      "🇫🇷", 80,  130, false, null,           "EUR"),
        BBox(42.0, 44.2, 12.4, 18.6) to CountryInfo("IT", "Italy",      "🇮🇹", 90,  130, false, null,           "EUR", "Tolls on most motorways"),
        BBox(41.3, 43.8, 2.0, 3.4)  to CountryInfo("ES", "Spain",       "🇪🇸", 90,  120, false, null,           "EUR"),
        BBox(49.0, 54.9, 14.1, 24.1) to CountryInfo("PL", "Poland",     "🇵🇱", 90,  140, false, null,           "PLN", "Fuel cheaper than Germany"),
        BBox(48.5, 51.1, 12.1, 22.6) to CountryInfo("CZ", "Czechia",    "🇨🇿", 90,  130, true,  "eVignette required",         "CZK"),
        BBox(47.7, 49.6, 16.8, 22.6) to CountryInfo("SK", "Slovakia",   "🇸🇰", 90,  130, true,  "eVignette required",         "EUR"),
        BBox(45.7, 48.6, 16.1, 22.9) to CountryInfo("HU", "Hungary",    "🇭🇺", 90,  130, true,  "eVignette required",         "HUF"),
        BBox(43.5, 46.5, 13.4, 28.6) to CountryInfo("RO", "Romania",    "🇷🇴", 90,  130, true,  "Rovinieta vignette",         "RON"),
        BBox(55.3, 57.8, 8.1, 15.2) to CountryInfo("DK", "Denmark",    "🇩🇰", 80,  130, false, null,           "DKK"),
        BBox(55.3, 69.1, 4.9, 31.2) to CountryInfo("NO", "Norway",     "🇳🇴", 80,  110, false, null,           "NOK", "Very expensive fuel"),
        BBox(55.3, 69.1, 10.9, 24.2) to CountryInfo("SE", "Sweden",    "🇸🇪", 80,  120, false, null,           "SEK"),
        BBox(45.4, 47.8, 6.7, 10.5) to CountryInfo("LI", "Liechtenstein","🇱🇮",80, 120, true, "Uses Swiss vignette",         "CHF"),
    )
}
