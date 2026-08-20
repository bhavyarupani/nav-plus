package com.navplus.core.settings

data class UserSettings(
    // ── Vehicle ──────────────────────────────────────────────────────────────
    val vehicleType: VehicleType    = VehicleType.ARROW,

    // ── Navigation alerts ────────────────────────────────────────────────────
    val showSpeedCameras: Boolean   = true,
    val showSpeedLimit: Boolean     = true,
    val showSignboards: Boolean     = true,
    val showLaneGuidance: Boolean   = true,
    val showBorderAlerts: Boolean   = true,
    val showRoadPersonality: Boolean = true,

    // ── Route options ────────────────────────────────────────────────────────
    val avoidTolls: Boolean         = false,
    val avoidHighways: Boolean      = false,
    val avoidFerries: Boolean       = false,

    // ── Display ──────────────────────────────────────────────────────────────
    val navMapTilt: Boolean         = true,
    val keepScreenOn: Boolean       = true,

    // ── Units ────────────────────────────────────────────────────────────────
    val units: DistanceUnits        = DistanceUnits.METRIC,
)

enum class DistanceUnits { METRIC, IMPERIAL }
