package com.navplus.app

import android.app.Application
import com.navplus.core.routing.graphhopper.GraphHopperEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class NavPlusApp : Application() {

    @Inject lateinit var graphHopperEngine: GraphHopperEngine

    override fun onCreate() {
        super.onCreate()
        // Load the pre-built routing graph if it exists (built from downloaded regions).
        Thread { graphHopperEngine.load() }.start()
    }
}
