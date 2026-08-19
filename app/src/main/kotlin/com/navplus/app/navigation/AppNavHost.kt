package com.navplus.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.navplus.core.common.model.LatLng
import com.navplus.core.navigation.LocationTracker
import com.navplus.core.navigation.TripRepository
import com.navplus.core.search.model.SearchResult
import com.navplus.feature.group.GroupScreen
import com.navplus.feature.home.HomeScreen
import com.navplus.feature.navigation.NavigationScreen
import com.navplus.feature.search.SearchScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    val tripRepository: TripRepository,
    locationTracker: LocationTracker,
) : ViewModel() {
    val currentLocation = locationTracker.locationUpdates()
        .map { it.latLng }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

object NavRoutes {
    const val HOME       = "home"
    const val SEARCH     = "search"
    const val NAVIGATION = "navigation"
    const val GROUP      = "group"
    const val REGIONS    = "regions"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val appVm: AppViewModel = hiltViewModel()
    val currentLocation: LatLng? by appVm.currentLocation.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = NavRoutes.HOME) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onSearchTap  = { navController.navigate(NavRoutes.SEARCH) },
                onGroupTap   = { navController.navigate(NavRoutes.GROUP)  },
                onRegionsTap = { navController.navigate(NavRoutes.REGIONS) },
            )
        }

        composable(NavRoutes.SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                nearLocation = currentLocation,
                onResultSelected = { result: SearchResult ->
                    appVm.tripRepository.setDestination(result.position, result.title)
                    navController.navigate(NavRoutes.NAVIGATION)
                },
            )
        }

        composable(NavRoutes.NAVIGATION) {
            NavigationScreen(
                onExit = { navController.popBackStack(NavRoutes.HOME, inclusive = false) },
            )
        }

        composable(NavRoutes.GROUP) {
            GroupScreen(
                onLeave = { navController.popBackStack() },
            )
        }

        composable(NavRoutes.REGIONS) {
            com.navplus.feature.regions.RegionsScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
}
