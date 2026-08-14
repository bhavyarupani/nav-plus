package com.roadpulse.auto.destination

import android.content.Context

data class SelectedDestination(
    val placeId: String,
    val title: String,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
)

class SelectedDestinationStore(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "selected_destination",
            Context.MODE_PRIVATE,
        )

    fun save(destination: SelectedDestination) {
        check(
            preferences
                .edit()
                .putString(KEY_PLACE_ID, destination.placeId)
                .putString(KEY_TITLE, destination.title)
                .putString(KEY_ADDRESS, destination.address)
                .putString(KEY_LATITUDE, destination.latitude?.toString())
                .putString(KEY_LONGITUDE, destination.longitude?.toString())
                .commit(),
        ) { "Unable to persist destination" }
    }

    fun load(): SelectedDestination? {
        val placeId =
            preferences.getString(KEY_PLACE_ID, null)?.takeIf(String::isNotBlank)
                ?: return null
        return SelectedDestination(
            placeId = placeId,
            title = preferences.getString(KEY_TITLE, null).orEmpty().ifBlank { "Selected destination" },
            address = preferences.getString(KEY_ADDRESS, null).orEmpty(),
            latitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull(),
            longitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull(),
        )
    }

    companion object {
        private const val KEY_PLACE_ID = "place_id"
        private const val KEY_TITLE = "title"
        private const val KEY_ADDRESS = "address"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
    }
}
