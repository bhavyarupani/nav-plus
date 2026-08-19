package com.navplus.app.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.navplus.core.navigation.TripRepository
import com.navplus.core.search.model.SearchResult
import com.navplus.feature.group.GroupScreen
import com.navplus.feature.home.HomeScreen
import com.navplus.feature.navigation.NavigationScreen
import com.navplus.feature.search.SearchScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    val tripRepository: TripRepository,
) : ViewModel()

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
