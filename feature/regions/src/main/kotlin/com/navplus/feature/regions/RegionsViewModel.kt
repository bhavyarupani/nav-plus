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
            region("de-bavaria",       "Bavaria",             "DE", 2_800_000_000L),
            region("de-bw",            "Baden-Württemberg",   "DE", 1_900_000_000L),
            region("de-nrw",           "North Rhine-Westphalia","DE",2_100_000_000L),
            region("de-north",         "Northern Germany",    "DE", 2_400_000_000L),
            region("at-all",           "Austria",             "AT", 1_600_000_000L),
            region("ch-all",           "Switzerland",         "CH", 1_200_000_000L),
            region("fr-idf",           "Île-de-France",       "FR",   900_000_000L),
            region("fr-south",         "Southern France",     "FR", 2_200_000_000L),
            region("it-north",         "Northern Italy",      "IT", 2_000_000_000L),
            region("nl-all",           "Netherlands",         "NL",   800_000_000L),
            region("be-all",           "Belgium",             "BE",   650_000_000L),
            region("pl-all",           "Poland",              "PL", 3_100_000_000L),
            region("cz-all",           "Czechia",             "CZ",   900_000_000L),
            region("es-all",           "Spain",               "ES", 3_500_000_000L),
        )

        private fun region(id: String, name: String, country: String, sizeBytes: Long) = Region(
            id = id,
            name = name,
            countryCode = country,
            sizeBytes = sizeBytes,
            mapUrl     = "$CDN/$id/map.mbtiles",
            routingUrl = "$CDN/$id/routing.osm-gh",
            searchUrl  = "$CDN/$id/search.photon",
            // Bounding boxes approximate — good enough for coverage detection
            boundsMinLat = 0.0, boundsMaxLat = 0.0,
            boundsMinLng = 0.0, boundsMaxLng = 0.0,
        )
    }
}
