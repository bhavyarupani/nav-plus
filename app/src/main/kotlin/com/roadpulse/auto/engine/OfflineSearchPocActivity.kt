package com.roadpulse.auto.engine

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.roadpulse.auto.traffic.RoadCoordinate

/**
 * Free-stack migration proof of concept: real on-device offline place search against the bundled
 * search-bremen.db (25,930 named OSM nodes indexed from the same Bremen extract used for map
 * tiles and routing). See ZERO_COST_ARCHITECTURE.md. Not linked from any app screen:
 * `adb shell am start -n com.roadpulse.auto/.engine.OfflineSearchPocActivity`
 */
class OfflineSearchPocActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView =
            TextView(this).apply {
                text = "Searching offline index…"
                textSize = 14f
                setPadding(32, 32, 32, 32)
            }
        setContentView(ScrollView(this).apply { addView(textView) })

        val engine = OfflineSearchEngine(applicationContext)
        // Bremen Hauptbahnhof - same coordinates already verified elsewhere in this migration.
        engine
            .search("Hauptbahnhof", nearCoordinate = RoadCoordinate(53.0829, 8.8134))
            .whenComplete { results, error ->
                runOnUiThread {
                    if (error != null) {
                        Log.e("OfflineSearchPoc", "Search failed", error)
                        textView.text = "FAILED: ${error.cause?.message ?: error.message}"
                        return@runOnUiThread
                    }
                    textView.text =
                        buildString {
                            appendLine("Offline search for \"Hauptbahnhof\" (Bremen index):")
                            appendLine("Results: ${results.size}")
                            results.forEach { result ->
                                appendLine("- ${result.title} (${result.subtitle}) @ ${result.coordinate}")
                            }
                        }
                    Log.i("OfflineSearchPoc", textView.text.toString())
                }
            }
    }
}
