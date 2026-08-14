package com.roadpulse.auto.traffic

enum class RoadFacilityType {
    WEBCAM,
    PARKING,
    CHARGING,
    RESTROOM,
}

enum class RestroomFeeStatus {
    FREE,
    PAID,
    UNKNOWN,
}

data class RoadFacility(
    val id: String,
    val roadId: String,
    val type: RoadFacilityType,
    val coordinate: RoadCoordinate,
    val title: String,
    val subtitle: String,
    val detail: String,
    val maximumChargingPowerKw: Int? = null,
    val lorrySpaces: Int? = null,
    val carSpaces: Int? = null,
    val amenities: List<String> = emptyList(),
    val restroomFeeStatus: RestroomFeeStatus = RestroomFeeStatus.UNKNOWN,
)

data class RoadFacilityResult(
    val facilities: List<RoadFacility>,
    val timestampMillis: Long,
    val usedSavedData: Boolean,
)
