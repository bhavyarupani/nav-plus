package com.navplus.core.common.model

data class LatLng(val lat: Double, val lng: Double) {
    companion object {
        val ZERO = LatLng(0.0, 0.0)
    }
}

fun LatLng.distanceTo(other: LatLng): Double {
    val r = 6_371_000.0
    val φ1 = Math.toRadians(lat)
    val φ2 = Math.toRadians(other.lat)
    val Δφ = Math.toRadians(other.lat - lat)
    val Δλ = Math.toRadians(other.lng - lng)
    val a = Math.sin(Δφ / 2).let { it * it } +
            Math.cos(φ1) * Math.cos(φ2) * Math.sin(Δλ / 2).let { it * it }
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

fun LatLng.bearingTo(other: LatLng): Double {
    val φ1 = Math.toRadians(lat)
    val φ2 = Math.toRadians(other.lat)
    val Δλ = Math.toRadians(other.lng - lng)
    val y = Math.sin(Δλ) * Math.cos(φ2)
    val x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ)
    return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
}
