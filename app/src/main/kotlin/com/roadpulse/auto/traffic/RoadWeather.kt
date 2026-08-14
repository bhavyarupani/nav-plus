package com.roadpulse.auto.traffic

enum class RoadSurfaceCondition(
    val severity: Int,
    val displayName: String,
) {
    UNKNOWN(0, "Unknown"),
    DRY(0, "Dry"),
    DAMP(1, "Damp"),
    SNOW(3, "Snow"),
    FROST(3, "Frost"),
    FREEZING_WETNESS(4, "Freezing wetness"),
    BLACK_ICE(5, "Black ice"),
}

data class RoadWeatherForecast(
    val stationId: String,
    val coordinate: RoadCoordinate,
    val forecastAtMillis: Long,
    val airTemperatureC: Double?,
    val surfaceTemperatureC: Double?,
    val dewPointC: Double?,
    val liquidPrecipitationMm: Double?,
    val solidPrecipitationMm: Double?,
    val rainProbabilityPercent: Double?,
    val snowProbabilityPercent: Double?,
    val condition: RoadSurfaceCondition,
)

data class RoadWeatherResult(
    val stationDistanceMeters: Int,
    val forecasts: List<RoadWeatherForecast>,
    val generatedAtMillis: Long,
    val usedSavedData: Boolean,
) {
    val mostSevere: RoadWeatherForecast?
        get() = forecasts.maxByOrNull { it.condition.severity }
}

data class WeatherWarning(
    val id: String,
    val coordinate: RoadCoordinate,
    val event: String,
    val headline: String,
    val description: String,
    val severity: String,
    val beginsAtMillis: Long?,
    val expiresAtMillis: Long?,
)

data class WeatherWarningResult(
    val warnings: List<WeatherWarning>,
    val timestampMillis: Long,
    val usedSavedData: Boolean,
)
