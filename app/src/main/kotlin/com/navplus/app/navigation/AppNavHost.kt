package com.navplus.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.navplus.feature.home.HomeScreen
import com.navplus.feature.navigation.NavigationScreen
import com.navplus.feature.search.SearchScreen

object NavRoutes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val NAVIGATION = "navigation"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = NavRoutes.HOME) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onSearchTap = { navController.navigate(NavRoutes.SEARCH) },
            )
        }

        composable(NavRoutes.SEARCH) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onResultSelected = { result ->
                    // TODO: set destination and route, then navigate to NAVIGATION
                    navController.navigate(NavRoutes.NAVIGATION)
                },
            )
        }

        composable(NavRoutes.NAVIGATION) {
            NavigationScreen(
                onExit = { navController.popBackStack(NavRoutes.HOME, inclusive = false) },
            )
        }
    }
}
