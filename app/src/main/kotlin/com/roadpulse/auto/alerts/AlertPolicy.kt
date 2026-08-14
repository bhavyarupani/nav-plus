package com.roadpulse.auto.alerts

import java.util.Locale

/**
 * Keeps legally sensitive alerts out of the driving UI where they are prohibited.
 * This is a first safety rail, not a substitute for a country-by-country legal review.
 */
object AlertPolicy {
    fun mayShow(
        alert: RoadAlert,
        countryCode: String,
        mode: AlertVisibilityMode,
    ): Boolean {
        if (mode == AlertVisibilityMode.PARKED_PLANNING) return true
        return mayShowWhileDriving(alert, countryCode)
    }

    fun mayShowWhileDriving(
        alert: RoadAlert,
        countryCode: String,
    ): Boolean {
        val normalizedCountry = countryCode.trim().uppercase(Locale.ROOT)
        return !(alert.type == AlertType.SPEED_CAMERA && normalizedCountry == "DE")
    }

    fun mayShowOpenGatsoPoi(
        poi: OpenGatsoPoi,
        countryCode: String,
        mode: AlertVisibilityMode,
    ): Boolean {
        if (mode == AlertVisibilityMode.PARKED_PLANNING) return true
        val normalizedCountry = countryCode.trim().uppercase(Locale.ROOT)
        if (!poi.isEnforcementLocation) return true
        return normalizedCountry.isNotBlank() && normalizedCountry != "DE"
    }
}
