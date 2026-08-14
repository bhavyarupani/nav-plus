package com.roadpulse.auto.traffic

enum class TrafficEventType {
    QUEUE,
    WARNING,
    ROADWORK,
    CLOSURE,
}

data class TrafficEvent(
    val id: String,
    val roadId: String,
    val type: TrafficEventType,
    val title: String,
    val direction: String,
    val detail: String,
    val delayMinutes: Int?,
    val startsAtMillis: Long?,
    val geometry: List<RoadCoordinate>,
    val source: String,
) {
    val start: RoadCoordinate?
        get() = geometry.firstOrNull()

    val end: RoadCoordinate?
        get() = geometry.lastOrNull()
}

data class TrafficEventResult(
    val events: List<TrafficEvent>,
    val timestampMillis: Long,
    val usedSavedData: Boolean,
    val unavailableRoadCount: Int,
)
