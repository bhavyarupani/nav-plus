package com.navplus.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navplus.app.navigation.AppNavHost
import com.navplus.app.ui.theme.NavPlusTheme
import com.navplus.core.navigation.RoadScenarioSimulator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var roadScenarioSimulator: RoadScenarioSimulator

    private var locationGranted by mutableStateOf(false)

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_NavPlus)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startRoadSimulation = intent?.action == ACTION_SIMULATE_DRIVE
        if (startRoadSimulation) {
            roadScenarioSimulator.start()
        }

        // Check if permission is already held
        locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        val permsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!locationGranted) {
            locationPermissionLauncher.launch(permsToRequest.toTypedArray())
        }

        setContent {
            NavPlusTheme {
                if (locationGranted) {
                    StartupGate(startRoadSimulation = startRoadSimulation) {
                        AppNavHost(startRoadSimulation = startRoadSimulation)
                    }
                } else {
                    LocationPermissionRationale(
                        onGrant = {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            )
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val ACTION_SIMULATE_DRIVE = "com.navplus.action.SIMULATE_DRIVE"
    }

    @Composable
    private fun StartupGate(
        startRoadSimulation: Boolean,
        content: @Composable () -> Unit,
    ) {
        var showLaunch by remember { mutableStateOf(true) }
        LaunchedEffect(startRoadSimulation) {
            delay(if (startRoadSimulation) 320L else 760L)
            showLaunch = false
        }
        if (showLaunch) {
            NavPlusLaunchScreen(startRoadSimulation)
        } else {
            content()
        }
    }

    @Composable
    private fun NavPlusLaunchScreen(startRoadSimulation: Boolean) {
        Surface(color = Color(0xFF090E18), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(modifier = Modifier.size(118.dp)) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        drawCircle(Color(0xFF111827), radius = size.minDimension * 0.48f, center = center)
                        drawCircle(
                            color = Color(0xFF38BDF8).copy(alpha = 0.42f),
                            radius = size.minDimension * 0.40f,
                            center = center,
                            style = Stroke(width = 5f),
                        )
                        drawLine(
                            color = Color(0xFF38BDF8),
                            start = Offset(size.width * 0.50f, size.height * 0.78f),
                            end = Offset(size.width * 0.50f, size.height * 0.30f),
                            strokeWidth = 8f,
                            cap = StrokeCap.Round,
                        )
                        drawLine(
                            color = Color(0xFF38BDF8),
                            start = Offset(size.width * 0.50f, size.height * 0.30f),
                            end = Offset(size.width * 0.65f, size.height * 0.22f),
                            strokeWidth = 8f,
                            cap = StrokeCap.Round,
                        )
                        drawCircle(Color.White, radius = 7f, center = Offset(size.width * 0.65f, size.height * 0.22f))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "Nav Plus",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (startRoadSimulation) "Starting drive simulation" else "Preparing your drive",
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    @Composable
    private fun LocationPermissionRationale(onGrant: () -> Unit) {
        Surface(color = Color(0xFF0F172A), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("📍", fontSize = 56.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Location needed",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Nav Plus needs your location to show your position on the map and provide turn-by-turn navigation.",
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(32.dp))
                    Button(onClick = onGrant) {
                        Text("Grant location access")
                    }
                }
            }
        }
    }
}
