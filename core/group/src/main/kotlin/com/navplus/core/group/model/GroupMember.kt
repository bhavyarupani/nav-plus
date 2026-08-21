package com.navplus.core.group.model

import com.navplus.core.common.model.LatLng

data class GroupMember(
    val id: String,
    val name: String,
    val color: String,
    val isLeader: Boolean = false,
    val location: LatLng? = null,
    val bearingDeg: Float = 0f,
    val speedKph: Float = 0f,
    val etaSec: Long? = null,
    val distanceRemainingMeters: Double? = null,
    val lastSeenMs: Long = 0L,
    val isOnline: Boolean = false,
    val hasDeviated: Boolean = false,
    val rejoinInfo: RejoinInfo? = null,
)

data class RejoinInfo(
    val distanceMeters: Double,
    val etaSec: Long,
    val locationName: String?,
)
