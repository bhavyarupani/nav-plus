package com.roadpulse.auto.settings

import android.content.Context
import androidx.core.content.edit
import com.roadpulse.auto.traffic.RoadInfrastructureType

val RoadInfrastructureType.label: String
    get() =
        when (this) {
            RoadInfrastructureType.TRAFFIC_SIGNAL -> "Traffic signals"
            RoadInfrastructureType.STOP_SIGN -> "Stop signs"
            RoadInfrastructureType.GIVE_WAY_SIGN -> "Give-way signs"
            RoadInfrastructureType.PRIORITY_ROAD_SIGN -> "Priority road signs"
            RoadInfrastructureType.PRIORITY_AT_JUNCTION_SIGN -> "Priority-at-junction signs"
            RoadInfrastructureType.SPEED_LIMIT_SIGN -> "Speed limit signs"
            RoadInfrastructureType.ROAD_RULE_START -> "Road-rule zone starts"
            RoadInfrastructureType.ROAD_RULE_END -> "Road-rule zone ends"
            RoadInfrastructureType.TRAFFIC_RESTRICTION -> "Traffic restrictions"
            RoadInfrastructureType.PEDESTRIAN_CROSSING -> "Pedestrian crossings"
            RoadInfrastructureType.RAILWAY_CROSSING -> "Railway crossings"
            RoadInfrastructureType.SCHOOL_ZONE -> "School zones"
            RoadInfrastructureType.TRAFFIC_CALMING -> "Traffic calming"
            RoadInfrastructureType.TUNNEL -> "Tunnels"
            RoadInfrastructureType.BRIDGE -> "Bridges"
            RoadInfrastructureType.DIMENSION_RESTRICTION -> "Dimension restrictions"
            RoadInfrastructureType.TOLL -> "Toll roads"
            RoadInfrastructureType.STEEP_GRADE -> "Steep grades"
            RoadInfrastructureType.SURFACE_HAZARD -> "Road surface hazards"
            RoadInfrastructureType.MOTORWAY_JUNCTION -> "Motorway exits"
            RoadInfrastructureType.OTHER_SIGN -> "Other signs"
        }

/**
 * Per-sign-type visibility, nested under the [DisplayLayer.ROAD_SIGNS] toggle: that toggle
 * still gates the whole category, and these decide which individual sign types show once
 * it's on. Same parked/driving split as [DisplayFilterStore].
 */
class RoadSignFilterStore(
    context: Context,
) {
    private val preferences =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isEnabled(
        drivingContext: DrivingContext,
        type: RoadInfrastructureType,
    ): Boolean = preferences.getBoolean(key(drivingContext, type), true)

    fun setEnabled(
        drivingContext: DrivingContext,
        type: RoadInfrastructureType,
        enabled: Boolean,
    ) {
        preferences.edit { putBoolean(key(drivingContext, type), enabled) }
    }

    private fun key(
        drivingContext: DrivingContext,
        type: RoadInfrastructureType,
    ) = "${drivingContext.name}_${type.name}"

    companion object {
        private const val PREFERENCES_NAME = "road_sign_filters"
    }
}
