package com.roadpulse.auto.navigation

import android.os.Handler
import android.os.Looper
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import java.util.concurrent.CopyOnWriteArraySet

/** In-process bridge from Google's turn-by-turn feed to the phone and Android Auto UI. */
object TurnByTurnState {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(NavInfo) -> Unit>()

    @Volatile
    var latest: NavInfo? = null
        private set

    fun publish(navInfo: NavInfo) {
        latest = navInfo
        mainHandler.post {
            listeners.forEach { listener -> listener(navInfo) }
        }
    }

    fun addListener(listener: (NavInfo) -> Unit) {
        listeners += listener
        latest?.let { navInfo -> mainHandler.post { listener(navInfo) } }
    }

    fun removeListener(listener: (NavInfo) -> Unit) {
        listeners -= listener
    }
}
