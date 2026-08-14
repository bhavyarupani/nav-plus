package com.roadpulse.auto.traffic

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class TrafficSnapshotStore(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFERENCES,
            Context.MODE_PRIVATE,
        )

    fun save(
        events: List<TrafficEvent>,
        timestampMillis: Long,
        usedSavedData: Boolean,
    ) {
        val encoded = JSONArray()
        events.take(MAX_STORED_EVENTS).forEach { event ->
            val start = event.start ?: return@forEach
            val end = event.end ?: start
            encoded.put(
                JSONObject()
                    .put("id", event.id)
                    .put("road", event.roadId)
                    .put("type", event.type.name)
                    .put("title", event.title)
                    .put("direction", event.direction)
                    .put("detail", event.detail)
                    .put("delay", event.delayMinutes)
                    .put("startLat", start.latitude)
                    .put("startLon", start.longitude)
                    .put("endLat", end.latitude)
                    .put("endLon", end.longitude),
            )
        }
        preferences.edit {
            putString(KEY_EVENTS, encoded.toString())
            putLong(KEY_TIMESTAMP, timestampMillis)
            putBoolean(KEY_SAVED, usedSavedData)
        }
    }

    fun load(): StoredTrafficSnapshot {
        val events =
            runCatching {
                val array = JSONArray(preferences.getString(KEY_EVENTS, "[]"))
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val start = RoadCoordinate(item.getDouble("startLat"), item.getDouble("startLon"))
                        val end = RoadCoordinate(item.getDouble("endLat"), item.getDouble("endLon"))
                        add(
                            TrafficEvent(
                                id = item.getString("id"),
                                roadId = item.getString("road"),
                                type = TrafficEventType.valueOf(item.getString("type")),
                                title = item.getString("title"),
                                direction = item.optString("direction"),
                                detail = item.optString("detail"),
                                delayMinutes = if (item.isNull("delay")) null else item.optInt("delay"),
                                startsAtMillis = null,
                                geometry = if (start == end) listOf(start) else listOf(start, end),
                                source = "Autobahn GmbH",
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        return StoredTrafficSnapshot(
            events = events,
            timestampMillis = preferences.getLong(KEY_TIMESTAMP, 0L),
            usedSavedData = preferences.getBoolean(KEY_SAVED, false),
        )
    }

    companion object {
        private const val PREFERENCES = "traffic_snapshot"
        private const val KEY_EVENTS = "events"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_SAVED = "saved"
        private const val MAX_STORED_EVENTS = 40
    }
}

data class StoredTrafficSnapshot(
    val events: List<TrafficEvent>,
    val timestampMillis: Long,
    val usedSavedData: Boolean,
)
