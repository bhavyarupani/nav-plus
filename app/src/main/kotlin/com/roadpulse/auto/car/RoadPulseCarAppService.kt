package com.roadpulse.auto.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.roadpulse.auto.R

class RoadPulseCarAppService : CarAppService() {
    override fun onDestroy() {
        DrivingSessionState.disconnected()
        super.onDestroy()
    }

    override fun createHostValidator(): HostValidator {
        val isDebug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (isDebug) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator
                .Builder(this)
                .addAllowedHosts(R.array.hosts_allowlist)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        DrivingSessionState.connected()
        return RoadPulseSession()
    }
}

private class RoadPulseSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = RoadPulseNavigationScreen(carContext)
}
