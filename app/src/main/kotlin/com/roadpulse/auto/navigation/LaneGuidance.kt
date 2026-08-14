package com.roadpulse.auto.navigation

import android.content.Context
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.Navigator

object LaneGuidance {
    /** Requests Google-generated maneuver and lane diagrams from the active route. */
    fun register(
        context: Context,
        navigator: Navigator,
    ): Boolean {
        val options =
            NavigationUpdatesOptions
                .builder()
                .setNumNextStepsToPreview(1)
                .setGeneratedStepImagesType(NavigationUpdatesOptions.GeneratedStepImagesType.BITMAP)
                .setDisplayMetrics(context.resources.displayMetrics)
                .build()
        return navigator.registerServiceForNavUpdates(
            context.packageName,
            NavInfoReceivingService::class.java.name,
            options,
        )
    }

    fun summary(navInfo: NavInfo): String {
        val lanes = navInfo.currentStep?.lanes.orEmpty()
        if (lanes.isEmpty()) return "Lane data is not available on this road yet"

        val recommended =
            lanes.mapIndexedNotNull { index, lane ->
                index.takeIf { lane.laneDirections().any { it.isRecommended == true } }
            }
        if (recommended.isEmpty()) return "Follow the current road; no specific lane is marked"

        val laneNumbers = recommended.joinToString(", ") { (it + 1).toString() }
        val noun = if (recommended.size == 1) "lane" else "lanes"
        return "Use $noun $laneNumbers of ${lanes.size}, counted from the left"
    }
}
