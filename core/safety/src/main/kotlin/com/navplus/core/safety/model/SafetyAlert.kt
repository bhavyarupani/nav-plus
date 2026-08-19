package com.navplus.core.safety.model

import com.navplus.core.common.model.RoadEventType
import com.navplus.core.common.model.Severity

data class SafetyAlert(
    val id: String,
    val type: RoadEventType,
    val distanceMeters: Double,
    val severity: Severity,
    val title: String,
    val speedLimitKph: Int? = null,
    val camera: SpeedCamera? = null,
)
