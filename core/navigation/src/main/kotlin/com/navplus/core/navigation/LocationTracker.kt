package com.navplus.core.navigation

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location as AndroidLocation
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Location
import com.navplus.core.common.model.bearingTo
import com.navplus.core.common.model.distanceTo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val roadScenarioSimulator: RoadScenarioSimulator,
) {
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long = 1_000): Flow<Location> {
        if (roadScenarioSimulator.isActive) {
            return roadScenarioSimulator.locationUpdates(intervalMs)
        }

        return callbackFlow {
            val filter = DriveLocationFilter()
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(500)
                .setMinUpdateDistanceMeters(1f)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.locations.forEach { loc ->
                        filter.update(loc)?.let { trySend(it) }
                    }
                }
            }

            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            awaitClose { fusedClient.removeLocationUpdates(callback) }
        }
    }
}

private class DriveLocationFilter {
    private var last: Location? = null
    private var lastRawPosition: LatLng? = null
    private var lastRawTimestampMs: Long? = null

    fun update(raw: AndroidLocation): Location? {
        val accuracy = if (raw.hasAccuracy()) raw.accuracy else Float.MAX_VALUE
        if (last != null && accuracy > MAX_MOVING_ACCURACY_METERS) return null

        val rawPosition = LatLng(raw.latitude, raw.longitude)
        val previous = last
        val rawSpeedMps = if (raw.hasSpeed()) raw.speed.coerceAtLeast(0f) else null
        val speedAccuracyMps = raw.speedAccuracyMps()
        val timestampMs = raw.time.takeIf { it > 0L } ?: System.currentTimeMillis()

        if (previous == null) {
            val firstSpeed = rawSpeedMps ?: 0f
            val first = Location(
                latLng = rawPosition,
                bearingDeg = normalizeBearing(if (raw.hasBearing()) raw.bearing else 0f),
                speedMps = firstSpeed,
                speedAccuracyMps = speedAccuracyMps,
                accuracyMeters = accuracy.takeIf { it != Float.MAX_VALUE } ?: 0f,
                altitudeMeters = if (raw.hasAltitude()) raw.altitude else 0.0,
                timestampMs = timestampMs,
            )
            last = first
            lastRawPosition = rawPosition
            lastRawTimestampMs = timestampMs
            return first
        }

        val dtSec = max((timestampMs - previous.timestampMs) / 1_000.0, 0.2)
        val distanceMeters = previous.latLng.distanceTo(rawPosition)
        val jumpSpeedMps = distanceMeters / dtSec

        if (accuracy > POOR_ACCURACY_METERS && jumpSpeedMps > MAX_REASONABLE_SPEED_MPS) {
            return previous.copy(timestampMs = timestampMs)
        }

        val derivedSpeedMps = speedFromRawPosition(rawPosition, timestampMs)
        val speedMps = estimateSpeed(
            previousSpeedMps = previous.speedMps,
            rawSpeedMps = rawSpeedMps,
            speedAccuracyMps = speedAccuracyMps,
            derivedSpeedMps = derivedSpeedMps,
            dtSec = dtSec,
        )

        val moving = speedMps >= MOVING_SPEED_MPS || distanceMeters >= MOVING_DISTANCE_METERS
        val positionAlpha = when {
            distanceMeters > SNAP_DISTANCE_METERS -> 1.0
            moving -> POSITION_ALPHA_MOVING
            else -> POSITION_ALPHA_STATIONARY
        }
        val smoothedPosition = previous.latLng.lerp(rawPosition, positionAlpha)

        val movementBearing = previous.latLng
            .takeIf { distanceMeters >= HEADING_FROM_MOVEMENT_METERS }
            ?.bearingTo(rawPosition)
            ?.toFloat()
        val candidateBearing = when {
            raw.hasBearing() && speedMps >= TRUST_GPS_BEARING_SPEED_MPS -> raw.bearing
            movementBearing != null && speedMps >= MOVING_SPEED_MPS -> movementBearing
            else -> previous.bearingDeg
        }
        val bearingAlpha = if (speedMps >= TRUST_GPS_BEARING_SPEED_MPS) BEARING_ALPHA_DRIVING else BEARING_ALPHA_SLOW
        val smoothedBearing = if (moving) {
            lerpBearing(previous.bearingDeg, candidateBearing, bearingAlpha)
        } else {
            previous.bearingDeg
        }

        return Location(
            latLng = smoothedPosition,
            bearingDeg = smoothedBearing,
            speedMps = speedMps,
            speedAccuracyMps = speedAccuracyMps,
            accuracyMeters = accuracy.takeIf { it != Float.MAX_VALUE } ?: previous.accuracyMeters,
            altitudeMeters = if (raw.hasAltitude()) raw.altitude else previous.altitudeMeters,
            timestampMs = timestampMs,
        ).also {
            last = it
            lastRawPosition = rawPosition
            lastRawTimestampMs = timestampMs
        }
    }

    private fun speedFromRawPosition(rawPosition: LatLng, timestampMs: Long): Float? {
        val previousRawPosition = lastRawPosition ?: return null
        val previousRawTimestampMs = lastRawTimestampMs ?: return null
        val dtSec = (timestampMs - previousRawTimestampMs) / 1_000.0
        if (dtSec !in MIN_SPEED_DT_SEC..MAX_SPEED_DT_SEC) return null
        val distanceMeters = previousRawPosition.distanceTo(rawPosition)
        if (distanceMeters < MIN_SPEED_DISTANCE_METERS) return 0f
        return (distanceMeters / dtSec).toFloat().coerceAtMost(MAX_REASONABLE_SPEED_MPS.toFloat())
    }

    private fun estimateSpeed(
        previousSpeedMps: Float,
        rawSpeedMps: Float?,
        speedAccuracyMps: Float?,
        derivedSpeedMps: Float?,
        dtSec: Double,
    ): Float {
        val candidate = speedCandidate(rawSpeedMps, speedAccuracyMps, derivedSpeedMps, previousSpeedMps)
        val maxIncrease = MAX_ACCEL_MPS2 * dtSec.toFloat()
        val maxDecrease = MAX_DECEL_MPS2 * dtSec.toFloat()
        val limited = candidate.coerceIn(
            (previousSpeedMps - maxDecrease).coerceAtLeast(0f),
            previousSpeedMps + maxIncrease,
        )
        val confidence = speedConfidence(rawSpeedMps, speedAccuracyMps, derivedSpeedMps)
        val change = abs(limited - previousSpeedMps)
        val alpha = when {
            change > LARGE_SPEED_CHANGE_MPS && confidence >= 0.65f -> 0.72f
            confidence >= 0.75f -> 0.58f
            confidence >= 0.45f -> 0.42f
            else -> 0.25f
        }
        val smoothed = previousSpeedMps + (limited - previousSpeedMps) * alpha
        return if (smoothed < STOPPED_SPEED_MPS && candidate < STOPPED_SPEED_MPS) 0f else smoothed
    }

    private fun speedCandidate(
        rawSpeedMps: Float?,
        speedAccuracyMps: Float?,
        derivedSpeedMps: Float?,
        previousSpeedMps: Float,
    ): Float {
        val rawReliable = rawSpeedMps != null &&
            (speedAccuracyMps == null || speedAccuracyMps <= USABLE_SPEED_ACCURACY_MPS)
        val derivedReliable = derivedSpeedMps != null
        return when {
            rawReliable && derivedReliable -> {
                val rawWeight = rawSpeedWeight(speedAccuracyMps)
                rawSpeedMps!! * rawWeight + derivedSpeedMps!! * (1f - rawWeight)
            }
            rawReliable -> rawSpeedMps!!
            derivedReliable -> derivedSpeedMps!!
            rawSpeedMps != null && rawSpeedMps < STOPPED_SPEED_MPS -> 0f
            else -> previousSpeedMps
        }
    }

    private fun rawSpeedWeight(speedAccuracyMps: Float?): Float {
        if (speedAccuracyMps == null) return 0.72f
        val normalized = ((USABLE_SPEED_ACCURACY_MPS - speedAccuracyMps) /
            (USABLE_SPEED_ACCURACY_MPS - GOOD_SPEED_ACCURACY_MPS)).coerceIn(0f, 1f)
        return 0.45f + normalized * 0.40f
    }

    private fun speedConfidence(
        rawSpeedMps: Float?,
        speedAccuracyMps: Float?,
        derivedSpeedMps: Float?,
    ): Float {
        val rawConfidence = when {
            rawSpeedMps == null -> 0f
            speedAccuracyMps == null -> 0.65f
            speedAccuracyMps <= GOOD_SPEED_ACCURACY_MPS -> 0.95f
            speedAccuracyMps <= USABLE_SPEED_ACCURACY_MPS -> 0.65f
            else -> 0.25f
        }
        val derivedConfidence = if (derivedSpeedMps != null) 0.55f else 0f
        return max(rawConfidence, derivedConfidence)
    }

    private fun LatLng.lerp(target: LatLng, alpha: Double): LatLng = LatLng(
        lat = lat + (target.lat - lat) * alpha,
        lng = lng + (target.lng - lng) * alpha,
    )

    private fun lerpBearing(from: Float, to: Float, alpha: Float): Float {
        val delta = ((to - from) % 360f + 540f) % 360f - 180f
        return normalizeBearing(from + delta * alpha)
    }

    private fun normalizeBearing(value: Float): Float = ((value % 360f) + 360f) % 360f

    private fun AndroidLocation.speedAccuracyMps(): Float? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()) {
            speedAccuracyMetersPerSecond
        } else {
            null
        }

    private companion object {
        const val MAX_MOVING_ACCURACY_METERS = 120f
        const val POOR_ACCURACY_METERS = 45f
        const val MAX_REASONABLE_SPEED_MPS = 75.0
        const val MIN_SPEED_DT_SEC = 0.25
        const val MAX_SPEED_DT_SEC = 4.0
        const val MIN_SPEED_DISTANCE_METERS = 1.2
        const val STOPPED_SPEED_MPS = 0.6f
        const val MOVING_SPEED_MPS = 1.4f
        const val TRUST_GPS_BEARING_SPEED_MPS = 3.0f
        const val MOVING_DISTANCE_METERS = 4.0
        const val HEADING_FROM_MOVEMENT_METERS = 6.0
        const val SNAP_DISTANCE_METERS = 120.0
        const val GOOD_SPEED_ACCURACY_MPS = 0.8f
        const val USABLE_SPEED_ACCURACY_MPS = 3.5f
        const val MAX_ACCEL_MPS2 = 5.0f
        const val MAX_DECEL_MPS2 = 8.0f
        const val LARGE_SPEED_CHANGE_MPS = 4.0f
        const val POSITION_ALPHA_MOVING = 0.65
        const val POSITION_ALPHA_STATIONARY = 0.20
        const val BEARING_ALPHA_DRIVING = 0.35f
        const val BEARING_ALPHA_SLOW = 0.12f
    }
}
