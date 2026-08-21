package com.navplus.core.routing

import com.navplus.core.common.model.Route

sealed class RoutingResult {
    data class Success(val routes: List<Route>) : RoutingResult()
    data class NoOfflineCoverage(val missingRegions: List<String>) : RoutingResult()
    data class Error(val cause: Throwable) : RoutingResult()
}
