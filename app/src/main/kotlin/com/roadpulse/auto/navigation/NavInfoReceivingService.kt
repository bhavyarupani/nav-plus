package com.roadpulse.auto.navigation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager

/** Receives the Navigation SDK's approximately once-per-second navigation feed. */
class NavInfoReceivingService : Service() {
    private val turnByTurnManager by lazy { TurnByTurnManager.createInstance() }
    private val messenger by lazy {
        Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    if (message.what == TurnByTurnManager.MSG_NAV_INFO) {
                        runCatching {
                            turnByTurnManager.readNavInfoFromBundle(message.data)
                        }.onSuccess(TurnByTurnState::publish)
                    } else {
                        super.handleMessage(message)
                    }
                }
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}
