package com.roadpulse.auto.alerts

import android.content.Context
import androidx.core.content.edit

/** Debug-only presentation test. It never reads or exposes a real camera location. */
class CameraAlertSimulation(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    fun enable(nowMillis: Long = System.currentTimeMillis()) {
        preferences.edit { putLong(ACTIVE_UNTIL_KEY, nowMillis + DURATION_MILLIS) }
    }

    fun disable() {
        preferences.edit { remove(ACTIVE_UNTIL_KEY) }
    }

    fun isActive(nowMillis: Long = System.currentTimeMillis()): Boolean =
        isWithinTestWindow(nowMillis, preferences.getLong(ACTIVE_UNTIL_KEY, 0L))

    fun alert(): RoadAlert =
        RoadAlert(
            id = "debug-fictional-camera",
            type = AlertType.SPEED_CAMERA,
            title = "TEST · Camera warning",
            detail = "600 m · fictional location",
            distanceMeters = 600,
            isSimulated = true,
        )

    companion object {
        const val DURATION_MINUTES = 10
        private const val DURATION_MILLIS = DURATION_MINUTES * 60_000L
        private const val PREFERENCES_NAME = "camera_alert_simulation"
        private const val ACTIVE_UNTIL_KEY = "active_until"

        fun isWithinTestWindow(
            nowMillis: Long,
            activeUntilMillis: Long,
        ): Boolean = activeUntilMillis > nowMillis && activeUntilMillis - nowMillis <= DURATION_MILLIS
    }
}
