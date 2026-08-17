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
     * Drives the "Check speed" amber breathing-ring nudge. Reverted to speed-limit-only per
     * explicit instruction, replacing an earlier camera-proximity trigger this project briefly
     * had - that version was a documented compliance risk under Germany's StVO Section 23
     * restriction on enforcement-warning devices (see PRIVACY.md history). This version has no
     * camera involvement at all: it fires purely from [SpeedComplianceLevel.NEAR_LIMIT], the
     * same level [evaluate] already computes from live speed vs. the mapped limit.
     */
    fun shouldShowCheckSpeed(level: SpeedComplianceLevel): Boolean = level == SpeedComplianceLevel.NEAR_LIMIT
}
