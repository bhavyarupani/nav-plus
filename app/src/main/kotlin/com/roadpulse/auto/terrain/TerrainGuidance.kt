package com.roadpulse.auto.terrain

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import com.google.android.libraries.navigation.Navigator
import com.roadpulse.auto.traffic.RoadCoordinate
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/** Maintains one throttled route-elevation profile shared by phone and Android Auto. */
object TerrainGuidance {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(ElevationProfileSummary?) -> Unit>()
    private val refreshInProgress = AtomicBoolean(false)
    private var repository: OpenMeteoElevationRepository? = null

    @Volatile
    var latest: ElevationProfileSummary? = null
        private set

    @Volatile
    private var lastAttemptMillis = 0L

    fun addListener(listener: (ElevationProfileSummary?) -> Unit) {
        listeners += listener
        mainHandler.post { listener(latest) }
    }

    fun removeListener(listener: (ElevationProfileSummary?) -> Unit) {
        listeners -= listener
    }

    fun clear() {
        latest = null
        listeners.forEach { listener -> mainHandler.post { listener(null) } }
    }

    fun refresh(
        context: Context,
        navigator: Navigator,
        force: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptMillis < REFRESH_INTERVAL_MILLIS) return
        if (!refreshInProgress.compareAndSet(false, true)) return
        lastAttemptMillis = now

        val route =
            runCatching {
                navigator.currentRouteSegment?.latLngs.orEmpty().map { coordinate ->
                    RoadCoordinate(coordinate.latitude, coordinate.longitude)
                }
            }.getOrDefault(emptyList())
        if (route.size < 2) {
            refreshInProgress.set(false)
            return
        }
        val current =
            lastKnownLocation(context)?.let { location ->
                RoadCoordinate(location.latitude, location.longitude)
            }
        val appContext = context.applicationContext
        Thread {
            val result =
                runCatching {
                    val readyRepository =
                        repository ?: OpenMeteoElevationRepository(appContext).also {
                            repository = it
                        }
                    readyRepository.profileForRoute(route, current)
                }
            mainHandler.post {
                refreshInProgress.set(false)
                result.onSuccess { summary ->
                    latest = summary
                    listeners.forEach { listener -> listener(summary) }
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(context: Context): Location? {
        if (
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val manager = context.getSystemService(LocationManager::class.java)
        return sequenceOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull(Location::getTime)
    }

    private const val REFRESH_INTERVAL_MILLIS = 2 * 60_000L
}
