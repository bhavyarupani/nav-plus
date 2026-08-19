package com.roadpulse.auto.engine

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** In-process broadcast of [RegionDownloadService]'s progress/completion, for `SettingsActivity`'s
 * "OFFLINE REGIONS" card to update live while a download runs - same listener-set-plus-main-thread-
 * `Handler` shape already used by `TerrainGuidance`/`SpeedLimitAheadGuidance`/etc. */
object RegionDownloadEvents {
    sealed class Event {
        data class Started(
            val regionId: String,
        ) : Event()

        data class Progress(
            val regionId: String,
            val bytesRead: Long,
            val totalBytes: Long,
        ) : Event()

        data class Finished(
            val regionId: String,
            val result: RegionDownloadResult,
        ) : Event()
    }

    private val listeners = CopyOnWriteArraySet<(Event) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: (Event) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Event) -> Unit) {
        listeners -= listener
    }

    fun publish(event: Event) {
        listeners.forEach { listener -> mainHandler.post { listener(event) } }
    }
}
