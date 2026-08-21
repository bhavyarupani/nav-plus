package com.navplus.feature.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.navplus.core.regions.RegionManager
import com.navplus.core.regions.model.Region
import com.navplus.core.regions.model.RegionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegionsViewModel @Inject constructor(
    private val regionManager: RegionManager,
) : ViewModel() {

    val regions = regionManager.allRegions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // Seed catalogue on first launch (IGNORE strategy means no-op if already seeded)
            regionManager.seedDefaultRegions(CATALOGUE)
        }
    }

    fun download(regionId: String) {
        viewModelScope.launch { regionManager.enqueueDownload(regionId) }
    }

    fun delete(regionId: String) {
        viewModelScope.launch { regionManager.deleteRegion(regionId) }
    }

    companion object {
        private const val CDN = "https://cdn.navplus.app/regions"

        val CATALOGUE = listOf(
            region("de-bavaria",       "Bavaria",              "DE", 2_800_000_000L, 47.20, 50.65,  8.90, 13.95),
            region("de-bw",            "Baden-Württemberg",    "DE", 1_900_000_000L, 47.50, 49.85,  7.45, 10.55),
            region("de-nrw",           "North Rhine-Westphalia","DE", 2_100_000_000L, 50.30, 52.55,  5.85,  9.55),
            region("de-north",         "Northern Germany",     "DE", 2_400_000_000L, 51.25, 55.10,  6.60, 14.50),
            region("at-all",           "Austria",              "AT", 1_600_000_000L, 46.35, 49.05,  9.50, 17.20),
            region("ch-all",           "Switzerland",          "CH", 1_200_000_000L, 45.80, 47.85,  5.90, 10.55),
            region("fr-idf",           "Île-de-France",        "FR",   900_000_000L, 48.10, 49.25,  1.45,  3.60),
            region("fr-south",         "Southern France",      "FR", 2_200_000_000L, 42.30, 46.85, -1.80,  7.75),
            region("it-north",         "Northern Italy",       "IT", 2_000_000_000L, 43.70, 47.15,  6.60, 13.90),
            region("nl-all",           "Netherlands",          "NL",   800_000_000L, 50.70, 53.70,  3.30,  7.25),
            region("be-all",           "Belgium",              "BE",   650_000_000L, 49.45, 51.55,  2.45,  6.45),
            region("pl-all",           "Poland",               "PL", 3_100_000_000L, 49.00, 54.85, 14.10, 24.20),
            region("cz-all",           "Czechia",              "CZ",   900_000_000L, 48.50, 51.10, 12.00, 18.90),
            region("es-all",           "Spain",                "ES", 3_500_000_000L, 35.90, 43.85, -9.40,  3.35),
        )

        private fun region(
            id: String,
            name: String,
            country: String,
            sizeBytes: Long,
            minLat: Double,
            maxLat: Double,
            minLng: Double,
            maxLng: Double,
        ) = Region(
            id = id,
            name = name,
            countryCode = country,
            sizeBytes = sizeBytes,
            mapUrl     = "$CDN/$id/map.mbtiles",
            routingUrl = "$CDN/$id/routing.osm-gh",
            searchUrl  = "$CDN/$id/search.photon",
            boundsMinLat = minLat, boundsMaxLat = maxLat,
            boundsMinLng = minLng, boundsMaxLng = maxLng,
        )
    }
}
