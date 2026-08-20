package com.navplus.core.regions.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "regions")
data class Region(
    @PrimaryKey val id: String,
    val name: String,
    val countryCode: String,
    val sizeBytes: Long,
    val status: RegionStatus = RegionStatus.AVAILABLE,
    val downloadedAt: Long? = null,
    val mapUrl: String,
    val routingUrl: String,
    val searchUrl: String,
    val boundsMinLat: Double,
    val boundsMinLng: Double,
    val boundsMaxLat: Double,
    val boundsMaxLng: Double,
)

enum class RegionStatus {
    AVAILABLE,
    QUEUED,
    DOWNLOADING,
    PROCESSING,
    READY,
    FAILED,
}
