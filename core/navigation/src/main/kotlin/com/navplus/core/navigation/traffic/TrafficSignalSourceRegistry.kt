package com.navplus.core.navigation.traffic

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrafficSignalSourceRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val configs: List<TrafficSignalSourceConfig> by lazy {
        context.assets.open(REGISTRY_ASSET).bufferedReader().use { reader ->
            parseRegistry(reader.readText())
        }
    }

    fun all(): List<TrafficSignalSourceConfig> = configs

    fun enabledForProvider(providerId: String): List<TrafficSignalSourceConfig> =
        configs.filter { it.provider == providerId && it.enabled }

    fun providerConfigs(providerId: String): List<TrafficSignalSourceConfig> =
        configs.filter { it.provider == providerId }

    companion object {
        private const val REGISTRY_ASSET = "traffic_signals/source_registry_de.json"

        fun parseRegistry(json: String): List<TrafficSignalSourceConfig> {
            val root = JSONObject(json)
            val sources = root.optJSONArray("sources") ?: JSONArray()
            return List(sources.length()) { index ->
                val item = sources.getJSONObject(index)
                TrafficSignalSourceConfig(
                    city = item.getString("city"),
                    region = item.optString("region").ifBlank { null },
                    country = item.optString("country", "DE"),
                    provider = item.getString("provider"),
                    capabilities = item.optJSONArray("capabilities").toCapabilities(),
                    endpointStatus = item.optString("endpointStatus").toEndpointStatus(),
                    accessType = item.optString("accessType"),
                    enabled = item.optBoolean("enabled", false),
                    endpoint = item.optString("endpoint").ifBlank { null },
                    notes = item.optString("notes"),
                    lastVerified = item.optString("lastVerified"),
                )
            }
        }

        private fun JSONArray?.toCapabilities(): Set<TrafficSignalCapability> {
            if (this == null) return emptySet()
            return buildSet {
                for (index in 0 until length()) {
                    runCatching {
                        add(TrafficSignalCapability.valueOf(getString(index)))
                    }
                }
            }
        }

        private fun String.toEndpointStatus(): TrafficSignalEndpointStatus =
            runCatching { TrafficSignalEndpointStatus.valueOf(this) }
                .getOrDefault(TrafficSignalEndpointStatus.CONFIGURED_BUT_UNAVAILABLE)
    }
}
