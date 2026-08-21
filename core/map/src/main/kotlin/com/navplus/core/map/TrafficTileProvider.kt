package com.navplus.core.map

object TrafficTileProvider {
    fun tomTomFlowTileUrls(apiKey: String): List<String> {
        val key = apiKey.trim()
        if (key.isEmpty()) return emptyList()
        return listOf(
            "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?tileSize=256&key=$key",
        )
    }
}
