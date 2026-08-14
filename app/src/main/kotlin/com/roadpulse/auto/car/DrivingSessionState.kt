package com.roadpulse.auto.car

object DrivingSessionState {
    @Volatile
    var isAndroidAutoConnected: Boolean = false
        private set

    fun connected() {
        isAndroidAutoConnected = true
    }

    fun disconnected() {
        isAndroidAutoConnected = false
    }
}
