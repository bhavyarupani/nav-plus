package com.roadpulse.auto.driving

enum class SpeedComplianceLevel {
    UNKNOWN,
    WITHIN_LIMIT,
    NEAR_LIMIT,
    OVER_LIMIT,
}

data class SpeedCompliance(
    val speedKph: Int?,
    val limitKph: Int?,
    val level: SpeedComplianceLevel,
    val shouldChime: Boolean,
)

object SpeedComplianceAdvisor {
    fun evaluate(
        speedKph: Int?,
        limitKph: Int?,
        chimeMarginKph: Int = 3,
    ): SpeedCompliance {
        if (speedKph == null || limitKph == null || speedKph < 0 || limitKph <= 0) {
            return SpeedCompliance(speedKph, limitKph, SpeedComplianceLevel.UNKNOWN, false)
        }

        val difference = speedKph - limitKph
        val level =
            when {
                difference > 0 -> SpeedComplianceLevel.OVER_LIMIT
                difference >= -3 -> SpeedComplianceLevel.NEAR_LIMIT
                else -> SpeedComplianceLevel.WITHIN_LIMIT
            }
        return SpeedCompliance(
            speedKph = speedKph,
            limitKph = limitKph,
            level = level,
            shouldChime = difference >= chimeMarginKph,
        )
    }

    /**
     * Drives the "Check speed" amber breathing-ring nudge. This is deliberately a single boolean
     * with no camera location, type, or countdown attached anywhere downstream - by explicit
     * user instruction this fires on camera proximity in every country, which is a documented
     * compliance risk under Germany's StVO Section 23 restriction on enforcement-warning
     * devices; see PRIVACY.md, "Speed compliance". [nearestCameraDistanceMeters] should come
     * from RouteCameraSnapshot.nearestCameraDistanceMeters, which is populated independent of
     * that same country's camera-marker/panel policy gate.
     */
    fun shouldShowCheckSpeed(nearestCameraDistanceMeters: Int?): Boolean =
        nearestCameraDistanceMeters != null && nearestCameraDistanceMeters in 0..CHECK_SPEED_CAMERA_RADIUS_METERS

    const val CHECK_SPEED_CAMERA_RADIUS_METERS = 3_000
}
