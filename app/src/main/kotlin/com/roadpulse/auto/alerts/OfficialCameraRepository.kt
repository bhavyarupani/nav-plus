package com.roadpulse.auto.alerts

import android.content.Context

class OfficialCameraRepository(
    context: Context,
) {
    private val updater = OfficialCameraDataUpdater(context.applicationContext)
    private val cached = mutableMapOf<OfficialCameraFeed, CachedFeed>()

    val latestTimestampMillis: Long
        get() = OfficialCameraFeed.entries.maxOfOrNull { updater.fileFor(it).lastModified() } ?: 0L

    fun enforcementLocationsInBounds(
        southLatitude: Double,
        westLongitude: Double,
        northLatitude: Double,
        eastLongitude: Double,
        referenceLatitude: Double,
        referenceLongitude: Double,
    ): List<NearbyOpenGatsoPoi> {
        require(southLatitude <= northLatitude)
        val records = OfficialCameraFeed.entries.flatMap(::readFeed)
        return records
            .asSequence()
            .filter { it.poi.isEnforcementLocation }
            .filter { it.sourceRecord.active != false }
            .filter { it.poi.latitude in southLatitude..northLatitude }
            .filter { longitudeIsInside(it.poi.longitude, westLongitude, eastLongitude) }
            .map { item -> item.toNearby(referenceLatitude, referenceLongitude) }
            .sortedBy(NearbyOpenGatsoPoi::distanceMeters)
            .toList()
    }

    fun nearestEnforcementLocations(
        latitude: Double,
        longitude: Double,
        maximumDistanceMeters: Int,
    ): List<NearbyOpenGatsoPoi> =
        OfficialCameraFeed.entries
            .flatMap(::readFeed)
            .asSequence()
            .filter { it.poi.isEnforcementLocation && it.sourceRecord.active != false }
            .map { item -> item.toNearby(latitude, longitude) }
            .filter { it.distanceMeters <= maximumDistanceMeters }
            .sortedBy(NearbyOpenGatsoPoi::distanceMeters)
            .toList()

    @Synchronized
    private fun readFeed(feed: OfficialCameraFeed): List<SourcedCameraPoi> {
        val file = updater.fileFor(feed)
        if (!file.isFile) return emptyList()
        val previous = cached[feed]
        if (previous != null && previous.lastModified == file.lastModified()) return previous.records
        val updatedAt = file.lastModified().takeIf { it > 0L }
        val records =
            OfficialCameraDataUpdater.parseFeed(feed, file).map { item ->
                item.copy(sourceRecord = item.sourceRecord.copy(sourceUpdatedAtMillis = updatedAt))
            }
        cached[feed] = CachedFeed(file.lastModified(), records)
        return records
    }

    private fun SourcedCameraPoi.toNearby(
        referenceLatitude: Double,
        referenceLongitude: Double,
    ): NearbyOpenGatsoPoi =
        NearbyOpenGatsoPoi(
            poi = poi,
            distanceMeters =
                OpenGatsoRepository.distanceMeters(
                    referenceLatitude,
                    referenceLongitude,
                    poi.latitude,
                    poi.longitude,
                ),
            sources = setOf(sourceRecord.source),
            sourceRecords = listOf(sourceRecord),
        )

    private fun longitudeIsInside(
        longitude: Double,
        west: Double,
        east: Double,
    ): Boolean = if (west <= east) longitude in west..east else longitude >= west || longitude <= east

    private data class CachedFeed(
        val lastModified: Long,
        val records: List<SourcedCameraPoi>,
    )
}
