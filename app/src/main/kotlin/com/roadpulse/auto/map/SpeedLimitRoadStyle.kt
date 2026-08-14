package com.roadpulse.auto.map

import android.graphics.Color
import com.roadpulse.auto.traffic.SpeedLimitRoadSection

object SpeedLimitRoadStyle {
    fun colour(section: SpeedLimitRoadSection): Int =
        when {
            section.unlimited -> Color.rgb(126, 87, 194)
            section.speedLimitKph == null -> Color.rgb(120, 144, 156)
            section.speedLimitKph <= 30 -> Color.rgb(0, 200, 83)
            section.speedLimitKph <= 50 -> Color.rgb(0, 172, 193)
            section.speedLimitKph <= 70 -> Color.rgb(253, 216, 53)
            section.speedLimitKph <= 100 -> Color.rgb(251, 140, 0)
            else -> Color.rgb(229, 57, 53)
        }

    const val LEGEND =
        "Road colours: green ≤30 · teal 40–50 · yellow 60–70 · " +
            "orange 80–100 · red 110+ · purple unrestricted"
}
