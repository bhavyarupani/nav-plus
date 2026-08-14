package com.roadpulse.auto.alerts

import java.io.File
import java.io.FileInputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NearbyOpenGatsoPoi(
    val poi: OpenGatsoPoi,
    val distanceMeters: Int,
    val sources: Set<CameraDataSource> = setOf(CameraDataSource.OPEN_GATSO),
    val sourceRecords: List<CameraSourceRecord> = sources.map { CameraSourceRecord(source = it) },
)

data class CameraSourceRecord(
    val source: CameraDataSource,
    val sourceId: String? = null,
    val rawType: String? = null,
    val roadName: String? = null,
    val direction: String? = null,
    val operator: String? = null,
    val areaName: String? = null,
    val locationDescription: String? = null,
    val installedDate: String? = null,
    val sourceUpdatedAtMillis: Long? = null,
    val active: Boolean? = null,
)

enum class CameraDataSource(
    val displayName: String,
    val shortName: String,
) {
    OPEN_GATSO("Open-GATSO", "GATSO"),
    OPENSTREETMAP("OpenStreetMap", "OSM"),
    FRANCE_INTERIOR("French Ministry of the Interior", "France"),
    BRUSSELS_MOBILITY("Brussels Mobility", "Brussels"),
    LUXEMBOURG_PCH("Luxembourg Roads Administration", "Luxembourg"),
}

class OpenGatsoRepository(
    private val dataFile: File,
) {
    fun enforcementLocationsInBounds(
        southLatitude: Double,
        westLongitude: Double,
        northLatitude: Double,
        eastLongitude: Double,
        referenceLatitude: Double,
        referenceLongitude: Double,
    ): List<NearbyOpenGatsoPoi> {
        require(southLatitude <= northLatitude)
        require(southLatitude in -90.0..90.0)
        require(northLatitude in -90.0..90.0)
        require(westLongitude in -180.0..180.0)
        require(eastLongitude in -180.0..180.0)
        if (!dataFile.isFile) return emptyList()

        return FileInputStream(dataFile).reader(Charsets.UTF_8).use { reader ->
            OpenGatsoCsvParser
                .parse(reader)
                .asSequence()
                .filter(OpenGatsoPoi::isEnforcementLocation)
                .filter { poi ->
                    poi.latitude in southLatitude..northLatitude &&
                        longitudeIsInside(poi.longitude, westLongitude, eastLongitude)
                }.map { poi ->
                    NearbyOpenGatsoPoi(
                        poi = poi,
                        distanceMeters =
                            distanceMeters(
                                referenceLatitude,
                                referenceLongitude,
                                poi.latitude,
                                poi.longitude,
                            ),
                        sourceRecords =
                            listOf(
                                CameraSourceRecord(
                                    source = CameraDataSource.OPEN_GATSO,
                                    locationDescription = poi.description.ifBlank { null },
                                    sourceUpdatedAtMillis = dataFile.lastModified().takeIf { it > 0L },
                                ),
                            ),
                    )
                }.sortedBy(NearbyOpenGatsoPoi::distanceMeters)
                .toList()
        }
    }

    fun nearestEnforcementLocations(
        latitude: Double,
        longitude: Double,
        limit: Int = 5,
        maximumDistanceMeters: Int = 50_000,
    ): List<NearbyOpenGatsoPoi> {
        require(limit in 1..100)
        require(maximumDistanceMeters > 0)
        if (!dataFile.isFile) return emptyList()

        return FileInputStream(dataFile).reader(Charsets.UTF_8).use { reader ->
            OpenGatsoCsvParser
                .parse(reader)
                .asSequence()
                .filter(OpenGatsoPoi::isEnforcementLocation)
                .map { poi ->
                    NearbyOpenGatsoPoi(
                        poi = poi,
                        distanceMeters =
                            distanceMeters(
                                latitude,
                                longitude,
                                poi.latitude,
                                poi.longitude,
                            ),
                        sourceRecords =
                            listOf(
                                CameraSourceRecord(
                                    source = CameraDataSource.OPEN_GATSO,
                                    locationDescription = poi.description.ifBlank { null },
                                    sourceUpdatedAtMillis = dataFile.lastModified().takeIf { it > 0L },
                                ),
                            ),
                    )
                }.filter { it.distanceMeters <= maximumDistanceMeters }
                .sortedBy(NearbyOpenGatsoPoi::distanceMeters)
                .take(limit)
                .toList()
        }
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        fun distanceMeters(
            latitudeA: Double,
            longitudeA: Double,
            latitudeB: Double,
            longitudeB: Double,
        ): Int {
            val latitudeDelta = Math.toRadians(latitudeB - latitudeA)
            val longitudeDelta = Math.toRadians(longitudeB - longitudeA)
            val latitudeARadians = Math.toRadians(latitudeA)
            val latitudeBRadians = Math.toRadians(latitudeB)
            val haversine =
                sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
                    cos(latitudeARadians) * cos(latitudeBRadians) *
                    sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
            return (2 * EARTH_RADIUS_METERS * asin(sqrt(haversine))).toInt()
        }

        private fun longitudeIsInside(
            longitude: Double,
            westLongitude: Double,
            eastLongitude: Double,
        ): Boolean =
            if (westLongitude <= eastLongitude) {
                longitude in westLongitude..eastLongitude
            } else {
                longitude >= westLongitude || longitude <= eastLongitude
            }
    }
}
