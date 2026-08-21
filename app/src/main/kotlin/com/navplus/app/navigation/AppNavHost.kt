package com.navplus.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.navplus.core.settings.SavedPlace
import com.navplus.core.settings.SettingsRepository
import com.navplus.feature.group.GroupScreen
import com.navplus.feature.home.HomeScreen
import com.navplus.feature.navigation.NavigationScreen
import com.navplus.feature.navigation.NavigationViewModel
import com.navplus.feature.search.SearchScreen
import com.navplus.feature.settings.SettingsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    val tripRepository: TripRepository,
    locationTracker: LocationTracker,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {
    val currentLocation = locationTracker.locationUpdates()
        .map { it.latLng }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setHomePlace(place: SavedPlace?) = viewModelScope.launch { settingsRepo.setHomePlace(place) }
    fun setWorkPlace(place: SavedPlace?) = viewModelScope.launch { settingsRepo.setWorkPlace(place) }
}

object NavRoutes {
    const val HOME       = "home"
    const val SEARCH     = "search"
    const val NAVIGATION = "navigation"
    const val GROUP      = "group"
    const val REGIONS    = "regions"
    const val SETTINGS   = "settings"
    const val TRIPS      = "trips"
    const val SET_PLACE  = "set_place"
}

@Composable
fun AppNavHost(startRoadSimulation: Boolean = false) {
    val navController = rememberNavController()
    val appVm: AppViewModel = hiltViewModel()
    val currentLocation: LatLng? by appVm.currentLocation.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = if (startRoadSimulation) NavRoutes.NAVIGATION else NavRoutes.HOME,
    ) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onSearchTap = { navController.navigate(NavRoutes.SEARCH) },
                onNavigateTo = { latLng, label ->
                    appVm.tripRepository.setDestination(latLng, label)
                    navController.navigate(NavRoutes.NAVIGATION)
                },
                onSetHome = { navController.navigate("${NavRoutes.SET_PLACE}/home") },
                onSetWork = { navController.navigate("${NavRoutes.SET_PLACE}/work") },
                onSettingsTap = { navController.navigate(NavRoutes.SETTINGS) },
            )
        }

        composable(NavRoutes.SEARCH) {
            val initialQuery = navController.previousBackStackEntry
                ?.savedStateHandle
                ?.remove<String>("initial_search_query")
            SearchScreen(
                onBack = { navController.popBackStack() },
                initialQuery = initialQuery,
                nearLocation = currentLocation,
                onResultSelected = { result: SearchResult ->
                    appVm.tripRepository.setDestination(result.position, result.title)
                    navController.navigate(NavRoutes.NAVIGATION)
                },
            )
        }

        composable(NavRoutes.NAVIGATION) {
            val navigationVm: NavigationViewModel = hiltViewModel()
            LaunchedEffect(startRoadSimulation) {
                if (startRoadSimulation) navigationVm.startRoadSimulation()
            }
            NavigationScreen(
                onExit = { navController.popBackStack(NavRoutes.HOME, inclusive = false) },
                vm = navigationVm,
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

        composable(NavRoutes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onSetHome = { navController.navigate("${NavRoutes.SET_PLACE}/home") },
                onSetWork = { navController.navigate("${NavRoutes.SET_PLACE}/work") },
            )
        }

        composable("${NavRoutes.SET_PLACE}/{type}") { backStackEntry ->
            val type = backStackEntry.arguments?.getString("type") ?: "home"
            SearchScreen(
                onBack = { navController.popBackStack() },
                nearLocation = currentLocation,
                onResultSelected = { result ->
                    val place = SavedPlace(result.position.lat, result.position.lng, result.title)
                    if (type == "home") appVm.setHomePlace(place) else appVm.setWorkPlace(place)
                    navController.popBackStack()
                },
            )
        }

        composable(NavRoutes.TRIPS) {
            TripsPlaceholderScreen(onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripsPlaceholderScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trips") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗺", style = MaterialTheme.typography.displayMedium)
                Text(
                    "Trip Planning",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Coming soon",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
