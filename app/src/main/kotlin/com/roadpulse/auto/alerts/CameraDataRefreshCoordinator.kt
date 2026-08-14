package com.roadpulse.auto.alerts

import android.content.Context

data class CameraDataRefreshSummary(
    val openGatsoPointCount: Int?,
    val official: OfficialCameraRefreshResult,
    val openGatsoFailure: String?,
) {
    val updatedSourceCount: Int = official.updated.size + if (openGatsoPointCount != null) 1 else 0
    val failureCount: Int = official.failures.size + if (openGatsoFailure != null) 1 else 0
}

class CameraDataRefreshCoordinator(
    context: Context,
) {
    private val openGatso = OpenGatsoDataUpdater(context.applicationContext)
    private val official = OfficialCameraDataUpdater(context.applicationContext)

    fun isRefreshDue(): Boolean = openGatso.isRefreshDue() || official.isRefreshDue()

    fun refresh(force: Boolean = false): CameraDataRefreshSummary {
        var openGatsoPointCount: Int? = null
        var openGatsoFailure: String? = null
        if (force || openGatso.isRefreshDue()) {
            runCatching { openGatso.downloadLatest() }
                .onSuccess { openGatsoPointCount = it.pointCount }
                .onFailure { openGatsoFailure = it.message ?: it.javaClass.simpleName }
        }
        val officialResult = official.refresh(force)
        return CameraDataRefreshSummary(openGatsoPointCount, officialResult, openGatsoFailure)
    }
}
