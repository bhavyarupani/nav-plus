package com.roadpulse.auto.alerts

object DemoAlertRepository {
    fun nextAlert(): RoadAlert =
        RoadAlert(
            id = "demo-hazard-1",
            type = AlertType.ROAD_HAZARD,
            title = "Road hazard ahead",
            detail = "600 m · simulated alert",
            distanceMeters = 600,
            isSimulated = true,
        )
}
