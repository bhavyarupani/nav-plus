package com.roadpulse.auto

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.roadpulse.auto.alerts.CameraAlertSimulation
import com.roadpulse.auto.alerts.CameraDataRefreshCoordinator
import com.roadpulse.auto.alerts.OfficialCameraDataUpdater
import com.roadpulse.auto.alerts.OpenGatsoDataUpdater
import com.roadpulse.auto.alerts.OpenStreetMapCameraRepository
import com.roadpulse.auto.map.SpeedLimitRoadStyle
import com.roadpulse.auto.quota.GoogleUsageGuard
import com.roadpulse.auto.settings.DisplayFilterStore
import com.roadpulse.auto.settings.DisplayLayer
import com.roadpulse.auto.settings.DrivingContext
import com.roadpulse.auto.settings.RoadSignFilterStore
import com.roadpulse.auto.settings.label
import com.roadpulse.auto.terrain.OpenMeteoElevationRepository
import com.roadpulse.auto.traffic.AutobahnTrafficRepository
import com.roadpulse.auto.traffic.DwdRoadWeatherRepository
import com.roadpulse.auto.traffic.RoadInfrastructureType
import java.io.File
import java.util.Locale

/** Dedicated settings screen: per-layer visibility filters plus data/privacy tools. */
class SettingsActivity : Activity() {
    private lateinit var displayFilters: DisplayFilterStore
    private lateinit var roadSignFilters: RoadSignFilterStore
    private lateinit var openGatsoUpdater: OpenGatsoDataUpdater
    private lateinit var officialCameraDataUpdater: OfficialCameraDataUpdater
    private lateinit var openStreetMapCameraRepository: OpenStreetMapCameraRepository
    private lateinit var cameraDataRefreshCoordinator: CameraDataRefreshCoordinator
    private lateinit var usageGuard: GoogleUsageGuard
    private var dataRefreshInProgress = false

    private val screenBackground by lazy { ContextCompat.getColor(this, R.color.rp_background) }
    private val panel by lazy { ContextCompat.getColor(this, R.color.rp_panel) }
    private val panelTop by lazy { ContextCompat.getColor(this, R.color.rp_panel_top) }
    private val panelControl by lazy { ContextCompat.getColor(this, R.color.rp_panel_control) }
    private val primaryColor by lazy { ContextCompat.getColor(this, R.color.rp_primary) }
    private val accent by lazy { ContextCompat.getColor(this, R.color.rp_accent) }
    private val textMuted by lazy { ContextCompat.getColor(this, R.color.rp_text_muted) }
    private val borderGlow by lazy { ContextCompat.getColor(this, R.color.rp_border_glow) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayFilters = DisplayFilterStore(this)
        roadSignFilters = RoadSignFilterStore(this)
        openGatsoUpdater = OpenGatsoDataUpdater(this)
        officialCameraDataUpdater = OfficialCameraDataUpdater(this)
        openStreetMapCameraRepository = OpenStreetMapCameraRepository(this)
        cameraDataRefreshCoordinator = CameraDataRefreshCoordinator(this)
        usageGuard = GoogleUsageGuard(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(screenBackground) }
        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(32))
            }
        scroll.addView(body)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + dp(8), view.paddingRight, bars.bottom + dp(8))
            insets
        }
        setContentView(scroll)

        body.addView(label("Settings", 24f, Color.WHITE).apply { setTypeface(typeface, Typeface.BOLD) })
        body.addView(
            label("What's shown while parked and what's shown while driving are separate.", 13f, textMuted),
            matchWidth(top = 4),
        )

        body.addView(
            sectionCard("WHILE PARKED", "The My Maps home screen, before you start driving.") { card ->
                PARKED_LAYERS.forEach { layer -> card.addView(filterRow(DrivingContext.PARKED, layer)) }
            },
            matchWidth(top = 20),
        )

        body.addView(
            sectionCard("WHILE DRIVING", "Turn-by-turn on the phone and Android Auto.") { card ->
                DRIVING_LAYERS.forEach { layer -> card.addView(filterRow(DrivingContext.DRIVING, layer)) }
            },
            matchWidth(top = 16),
        )

        body.addView(dataAndPrivacyCard(), matchWidth(top = 16))
    }

    private fun filterRow(
        context: DrivingContext,
        layer: DisplayLayer,
    ): android.view.View {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nestedPanel = if (layer == DisplayLayer.ROAD_SIGNS) signTypePanel(context) else null

        lateinit var expandLabel: TextView
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                val text =
                    LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        if (nestedPanel != null) {
                            isClickable = true
                            setOnClickListener {
                                val expanding = nestedPanel.visibility != android.view.View.VISIBLE
                                nestedPanel.visibility = if (expanding) android.view.View.VISIBLE else android.view.View.GONE
                                expandLabel.text = if (expanding) "Hide individual signs ▴" else "Choose individual signs ▾"
                            }
                        }
                    }
                text.addView(label(layer.label, 15f, Color.WHITE))
                text.addView(
                    label(layer.description, 12f, textMuted).apply { setLineSpacing(0f, 1.1f) },
                    matchWidth(top = 2),
                )
                if (nestedPanel != null) {
                    expandLabel =
                        label("Choose individual signs ▾", 12f, accent).apply {
                            setTypeface(typeface, Typeface.BOLD)
                        }
                    text.addView(expandLabel, matchWidth(top = 4))
                }
                addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(
                    Switch(this@SettingsActivity).apply {
                        isChecked = displayFilters.isEnabled(context, layer)
                        setOnCheckedChangeListener { _, checked -> displayFilters.setEnabled(context, layer, checked) }
                    },
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).also { it.marginStart = dp(12) },
                )
            }
        container.addView(row)
        nestedPanel?.let { container.addView(it, matchWidth(top = 2)) }
        return container
    }

    private fun signTypePanel(context: DrivingContext): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(dp(2), dp(2), dp(2), dp(6))
            RoadInfrastructureType.values().forEach { type ->
                addView(
                    LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(14), dp(5), 0, dp(5))
                        addView(
                            label(type.label, 13f, textMuted),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                        addView(
                            Switch(this@SettingsActivity).apply {
                                isChecked = roadSignFilters.isEnabled(context, type)
                                setOnCheckedChangeListener { _, checked ->
                                    roadSignFilters.setEnabled(context, type, checked)
                                }
                            },
                            LinearLayout
                                .LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).also { it.marginStart = dp(12) },
                        )
                    },
                )
            }
        }

    private fun sectionCard(
        title: String,
        subtitle: String,
        fill: (LinearLayout) -> Unit,
    ): android.view.View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = roundedPanel(panel, 20)
            addView(
                label(title, 12f, accent).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    letterSpacing = .1f
                },
            )
            addView(label(subtitle, 12f, textMuted), matchWidth(top = 2))
            val card = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.VERTICAL }
            fill(card)
            addView(card, matchWidth(top = 8))
        }

    private fun dataAndPrivacyCard(): android.view.View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedPanel(panel, 20)

            addView(
                label("DATA & PRIVACY", 12f, accent).apply {
                    setTypeface(typeface, Typeface.BOLD)
                    letterSpacing = .1f
                },
            )

            val dataStatus = label(currentCameraDataStatus(), 13f, textMuted)
            addView(dataStatus, matchWidth(top = 10))
            val updateButton = makeButton("Update offline camera data", primary = true) {}
            updateButton.setOnClickListener {
                if (dataRefreshInProgress) {
                    dataStatus.text = "A camera-data refresh is already running."
                    return@setOnClickListener
                }
                dataRefreshInProgress = true
                updateButton.isEnabled = false
                dataStatus.text = "Updating and validating every configured camera source…"
                Thread {
                    runCatching { cameraDataRefreshCoordinator.refresh(force = true) }
                        .onSuccess { summary ->
                            runOnUiThread {
                                dataStatus.text =
                                    buildString {
                                        append(currentCameraDataStatus())
                                        append("\nLast update: ${summary.updatedSourceCount} sources refreshed")
                                        if (summary.failureCount > 0) {
                                            append("; ${summary.failureCount} unavailable (saved data kept)")
                                        }
                                        append('.')
                                    }
                                updateButton.isEnabled = true
                                dataRefreshInProgress = false
                            }
                        }.onFailure { error ->
                            runOnUiThread {
                                dataStatus.text = "Update failed: ${error.message}"
                                updateButton.isEnabled = true
                                dataRefreshInProgress = false
                            }
                        }
                }.start()
            }
            addView(updateButton, matchWidth(top = 10))

            addView(label(SpeedLimitRoadStyle.LEGEND, 12f, textMuted), matchWidth(top = 14))

            val searchQuota = usageGuard.searchRequests.snapshot()
            val navigationQuota = usageGuard.navigationDestinations.snapshot()
            addView(
                label(
                    "Google cost guard: ${searchQuota.remaining}/1,000 searches and " +
                        "${navigationQuota.remaining}/1,000 destinations remain this month.",
                    13f,
                    textMuted,
                ),
                matchWidth(top = 14),
            )
            addView(makeButton("Privacy and data use", primary = false) { showPrivacyNotice() }, matchWidth(top = 10))
            addView(
                makeButton("Test fictional Android Auto alert", primary = false) {
                    CameraAlertSimulation(this@SettingsActivity).enable()
                    dataStatus.text = "Fictional test enabled for 10 minutes."
                },
                matchWidth(top = 8),
            )
            addView(
                label(
                    OpenGatsoDataUpdater.ATTRIBUTION +
                        "\n" + OfficialCameraDataUpdater.ATTRIBUTION +
                        "\n" + AutobahnTrafficRepository.ATTRIBUTION +
                        "\n" + DwdRoadWeatherRepository.ATTRIBUTION +
                        "\n" + OpenMeteoElevationRepository.ATTRIBUTION +
                        "\nRoad signs, signals, additional camera data, and the supermarket/fuel " +
                        "stop search use © OpenStreetMap contributors (ODbL)." +
                        "\nFuel prices © Tankerkoenig.de (CC BY 4.0)." +
                        "\nCharging data © Open Charge Map contributors.",
                    11f,
                    textMuted,
                ),
                matchWidth(top = 14),
            )
        }

    private fun showPrivacyNotice() {
        AlertDialog
            .Builder(this)
            .setTitle("My Maps privacy")
            .setMessage(
                "My Maps stores your selected destination, monthly usage counters, display " +
                    "filter choices, and downloaded Open-GATSO, official-government, and " +
                    "OpenStreetMap camera data only on this phone. My Maps has no server. " +
                    "Google Maps Platform processes Google-powered search and navigation data " +
                    "under Google's terms. If an optimized supermarket or fuel stop is enabled, " +
                    "a corridor around the remaining route is sent to the public OpenStreetMap " +
                    "Overpass service (not Google) so My Maps can find candidates and evaluate " +
                    "their mapped opening hours locally; results are cached on this phone and " +
                    "not otherwise retained. Real German enforcement locations are displayed " +
                    "only for stationary phone planning and stay out of Android Auto. " +
                    "When the camera or road-sign layer is on, My Maps may send the visible map " +
                    "bounds to the public OpenStreetMap Overpass service and caches the response " +
                    "locally. A signal marker is not a live red/amber/green reading unless an " +
                    "authority phase feed is explicitly connected. During navigation, the bounds " +
                    "around up to 15 km of route ahead may be sent to OpenStreetMap Overpass for " +
                    "mapped speed-limit road geometry; matching and time-to-change calculations " +
                    "stay on the phone. During navigation, rounded route coordinates are sent to " +
                    "Open-Meteo for terrain elevation and cached locally for offline reuse. " +
                    "Terrain-derived slope is an estimate and may be wrong on bridges, tunnels, " +
                    "and elevated roads.",
            ).setPositiveButton("Close", null)
            .show()
    }

    private fun hasOfflineCameraData(): Boolean =
        openGatsoUpdater.currentDataFile().isFile || officialCameraDataUpdater.statuses().isNotEmpty()

    private fun currentCameraDataStatus(): String {
        val lines = mutableListOf<String>()
        openGatsoUpdater.currentDataFile().takeIf(File::isFile)?.let { file ->
            lines += "Open-GATSO: ${formatRelativeTime(file.lastModified())} " +
                "(${formatStorageSize(file.length())})"
        }
        officialCameraDataUpdater.statuses().forEach { status ->
            val count =
                status.pointCount
                    .takeIf { it > 0 }
                    ?.let { " · $it points" }
                    .orEmpty()
            lines += "${status.feed.source.displayName}: " +
                "${formatRelativeTime(status.file.lastModified())}$count"
        }
        if (openStreetMapCameraRepository.storedRegionCount > 0) {
            val timestamp =
                openStreetMapCameraRepository.lastResultTimestampMillis
                    .takeIf { it > 0L }
                    ?.let { " · data ${formatRelativeTime(it)}" }
                    .orEmpty()
            lines += "OpenStreetMap: ${openStreetMapCameraRepository.storedRegionCount} saved regions " +
                "(${formatStorageSize(openStreetMapCameraRepository.storedBytes)})$timestamp"
        }
        if (!hasOfflineCameraData()) return "Offline camera data has not been downloaded."
        return lines.joinToString("\n")
    }

    private fun formatRelativeTime(timestampMillis: Long): CharSequence =
        android.text.format.DateUtils.getRelativeTimeSpanString(
            timestampMillis,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS,
        )

    private fun formatStorageSize(bytes: Long): String =
        when {
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
            else -> "$bytes B"
        }

    private fun makeButton(
        text: String,
        primary: Boolean,
        onClick: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(if (primary) Color.WHITE else textMuted)
        backgroundTintList = ColorStateList.valueOf(if (primary) primaryColor else panelControl)
        setOnClickListener { onClick() }
        minHeight = dp(48)
        stateListAnimator = null
    }

    private fun roundedPanel(
        color: Int,
        radiusDp: Int,
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        colors = intArrayOf(panelTop, color)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), borderGlow)
    }

    private fun label(
        text: String,
        sizeSp: Float,
        color: Int,
    ) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        setLineSpacing(0f, 1.12f)
    }

    private fun matchWidth(top: Int = 0) =
        LinearLayout
            .LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val PARKED_LAYERS =
            listOf(
                DisplayLayer.SPEED_CAMERAS,
                DisplayLayer.ROAD_SIGNS,
                DisplayLayer.AUTOBAHN_TRAFFIC,
                DisplayLayer.AUTOBAHN_FACILITIES,
                DisplayLayer.WEATHER,
            )
        private val DRIVING_LAYERS =
            listOf(
                DisplayLayer.SPEED_CAMERAS,
                DisplayLayer.ROAD_SIGNS,
                DisplayLayer.AUTOBAHN_TRAFFIC,
                DisplayLayer.AUTOBAHN_FACILITIES,
                DisplayLayer.WEATHER,
                DisplayLayer.TERRAIN,
                DisplayLayer.SPEED_LIMIT_AHEAD,
                DisplayLayer.LANE_GUIDANCE,
            )
    }
}
