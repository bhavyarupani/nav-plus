package com.roadpulse.auto.engine

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.roadpulse.auto.traffic.RoadCoordinate
import java.util.concurrent.CompletableFuture

/**
 * Free-stack migration proof of concept: real on-device GraphHopper route calculation from the
 * bundled pre-built Bremen graph. See ZERO_COST_ARCHITECTURE.md. Not linked from any app screen:
 * `adb shell am start -n com.roadpulse.auto/.engine.GraphHopperPocActivity`
 */
class GraphHopperPocActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView =
            TextView(this).apply {
                text = "Calculating route on-device via GraphHopper…"
                textSize = 14f
                setPadding(32, 32, 32, 32)
            }
        setContentView(ScrollView(this).apply { addView(textView) })

        val engine = GraphHopperRoutingEngine(applicationContext)
        CompletableFuture
            .supplyAsync {
                // Bremen Hauptbahnhof -> Bremen Airport - same coordinates already verified on
                // the dev machine's JVM test to produce a correct real route.
                engine.calculateRoute(
                    origin = RoadCoordinate(53.0829, 8.8134),
                    destination = RoadCoordinate(53.0475, 8.7864),
                )
            }.thenCompose { it }
            .whenComplete { routes, error ->
                runOnUiThread {
                    if (error != null) {
                        Log.e("GraphHopperPoc", "Route calculation failed", error)
                        textView.text = "FAILED: ${error.cause?.message ?: error.message}"
                        return@runOnUiThread
                    }
                    val route = routes.first()
                    textView.text =
                        buildString {
                            appendLine("Route calculated on-device via GraphHopper (Bremen graph):")
                            appendLine("Distance: ${route.distanceMeters} m")
                            appendLine("Duration: ${route.durationSeconds} s")
                            appendLine("Geometry points: ${route.geometry.size}")
                            appendLine("Alternatives: ${routes.size}")
                        }
                    Log.i("GraphHopperPoc", textView.text.toString())
                }
            }
    }
}
