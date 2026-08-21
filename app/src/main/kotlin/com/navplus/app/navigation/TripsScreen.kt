package com.navplus.app.navigation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.navplus.core.navigation.TripEventMarker
import com.navplus.core.navigation.TripEventSeverity
import com.navplus.core.navigation.TripInsight
import com.navplus.core.navigation.TripInsightsRepository
import com.navplus.core.navigation.TripRoadName
import com.navplus.core.navigation.TripRoadType
import com.navplus.core.navigation.TripTimelinePoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val repository: TripInsightsRepository,
) : ViewModel() {
    val trips: StateFlow<List<TripInsight>> = repository.trips
    fun deleteTrip(id: String) = repository.deleteTrip(id)
    fun clearAll() = repository.clearAll()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    onBack: () -> Unit,
    vm: TripsViewModel = hiltViewModel(),
) {
    val trips by vm.trips.collectAsStateWithLifecycle()
    var selectedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trips) {
        if (selectedId == null || trips.none { it.id == selectedId }) {
            selectedId = trips.firstOrNull()?.id
        }
    }
    val selected = trips.firstOrNull { it.id == selectedId }

    Scaffold(
        containerColor = Color(0xFFF4F7F6),
        topBar = {
            TopAppBar(
                title = { Text("Trip Insights") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Trip options")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (selected == null) {
            EmptyTrips(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    TripSelector(
                        trips = trips,
                        selectedId = selected.id,
                        onSelect = { selectedId = it },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                item { TripHero(selected, Modifier.padding(horizontal = 12.dp)) }
                item { ScoreGrid(selected, Modifier.padding(horizontal = 12.dp)) }
                item { PerformanceSection(selected, Modifier.padding(horizontal = 12.dp)) }
                item { SafetyNavigationSection(selected, Modifier.padding(horizontal = 12.dp)) }
                item { RoadSection(selected, Modifier.padding(horizontal = 12.dp)) }
                item { ExperienceSection(selected, Modifier.padding(horizontal = 12.dp)) }
                item {
                    DataStorageSection(
                        trip = selected,
                        onDelete = { vm.deleteTrip(selected.id) },
                        onClearAll = vm::clearAll,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                item { Spacer(Modifier.height(18.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyTrips(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TripRank", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color(0xFF101827))
            Text("Completed trips will appear here", color = Color(0xFF64748B), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun TripSelector(
    trips: List<TripInsight>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (trips.size <= 1) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        trips.take(4).forEach { trip ->
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(58.dp)
                    .clickable { onSelect(trip.id) },
                shape = RoundedCornerShape(12.dp),
                color = if (trip.id == selectedId) Color(0xFF111827) else Color.White,
                shadowElevation = if (trip.id == selectedId) 5.dp else 1.dp,
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.Center) {
                    Text(
                        trip.title,
                        color = if (trip.id == selectedId) Color.White else Color(0xFF111827),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                    )
                    Text(
                        "${trip.tripRank} · ${trip.distanceKm.oneDecimal()} km",
                        color = if (trip.id == selectedId) Color(0xFF93C5FD) else Color(0xFF64748B),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TripHero(trip: TripInsight, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF111827), Color(0xFF243B53), Color(0xFF0F766E)),
                    ),
                    RoundedCornerShape(22.dp),
                )
                .padding(18.dp),
        ) {
            Column {
                Text(trip.dateLabel, color = Color(0xFFB6E3D4), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    trip.title,
                    color = Color.White,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(trip.subtitle, color = Color(0xFFCBD5E1), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    ScoreRing(score = trip.tripRank, label = "TripRank", modifier = Modifier.size(116.dp))
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HeroMetric("${trip.distanceKm.oneDecimal()} km", "distance")
                        HeroMetric("${trip.drivingMinutes / 60}h ${trip.drivingMinutes % 60}m", "driving")
                        HeroMetric("${trip.stoppedMinutes}m", "stopped")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetric(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun ScoreGrid(trip: TripInsight, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreCard("Safety", trip.safetyScore, "#22C55E", Modifier.weight(1f))
            ScoreCard("Smooth", trip.smoothnessScore, "#38BDF8", Modifier.weight(1f))
            ScoreCard("Efficient", trip.efficiencyScore, "#F59E0B", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreCard("Navigate", trip.navigationScore, "#6366F1", Modifier.weight(1f))
            ScoreCard("Explore", trip.explorationScore, "#A855F7", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ScoreCard(title: String, score: Int, hex: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.height(88.dp), shape = RoundedCornerShape(16.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color(0xFF475569), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$score", color = hex.toColor(), fontSize = 28.sp, fontWeight = FontWeight.Black)
                Text("/100", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            LinearProgressIndicator(
                progress = { score / 100f },
                modifier = Modifier.fillMaxWidth().height(5.dp),
                color = hex.toColor(),
                trackColor = Color(0xFFE2E8F0),
            )
        }
    }
}

@Composable
private fun PerformanceSection(trip: TripInsight, modifier: Modifier = Modifier) {
    InsightSection(title = "Performance", modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Top speed", "${trip.topSpeedKph}", "km/h", "verified", Modifier.weight(1f))
            MetricCard("Moving avg", "${trip.movingAverageKph}", "km/h", "no stops", Modifier.weight(1f))
            MetricCard("Within limit", "${trip.distanceWithinLimitPercent}", "%", "distance", Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        SpeedTimeline(trip.speedTimeline, trip.eventTimeline)
    }
}

@Composable
private fun SafetyNavigationSection(trip: TripInsight, modifier: Modifier = Modifier) {
    InsightSection(title = "Safety + Navigation", modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Hard events", "${trip.hardEvents}", "", "${trip.hardBrakes} brake · ${trip.harshCorners} corner", Modifier.weight(1f))
            MetricCard("Route fit", "${trip.routeAdherencePercent}", "%", "${trip.missedTurns} missed turns", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallPill("Cameras", "${trip.cameraZones} zones · ${trip.cameraCompliantPercent}% ok", "#DC2626", Modifier.weight(1f))
            SmallPill("Lanes", "${trip.laneConfidencePercent}% · ${trip.complexJunctions} complex", "#2563EB", Modifier.weight(1f))
            SmallPill("Roundabouts", "${trip.roundabouts}", "#0F766E", Modifier.weight(1f))
        }
    }
}

@Composable
private fun RoadSection(trip: TripInsight, modifier: Modifier = Modifier) {
    InsightSection(title = "Road + Exploration", modifier = modifier) {
        RoadSplitBar(trip.roadTypes)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Roads driven", color = Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                trip.roadsDriven.take(4).forEach { RoadNameRow(it) }
            }
            Column(Modifier.weight(1f)) {
                Text("Elevation", color = Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                MetricLine("Highest", "${trip.highestAltitudeMeters} m")
                MetricLine("Ascent", "${trip.totalAscentMeters} m")
                MetricLine("Countries", trip.countries.joinToString(" · "))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExperienceSection(trip: TripInsight, modifier: Modifier = Modifier) {
    InsightSection(title = "Real-World Moments", modifier = modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (trip.weatherMoments + trip.realWorldMoments).take(8).forEach { moment ->
                TagChip(moment)
            }
        }
        trip.convoySummary?.let {
            Spacer(Modifier.height(10.dp))
            SmallPill("Convoy", it, "#6366F1", Modifier.fillMaxWidth())
        }
        if (trip.personalRecords.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Records", color = Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                trip.personalRecords.forEach { record -> RecordChip(record) }
            }
        }
    }
}

@Composable
private fun DataStorageSection(
    trip: TripInsight,
    onDelete: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InsightSection(title = "Data + Privacy", modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("Quality", trip.dataQuality.score.toString(), "", trip.dataQuality.label, Modifier.weight(1f))
            MetricCard("Stored", trip.storage.estimatedSizeMb.oneDecimal(), "MB", if (trip.storage.rawTraceKept) "detail kept" else "summary only", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(trip.dataQuality.note, color = Color(0xFF64748B), fontSize = 12.sp)
        Text(trip.storage.privacyLabel, color = Color(0xFF64748B), fontSize = 12.sp)
        Text(trip.storage.retentionLabel, color = Color(0xFF64748B), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete trip")
            }
            Button(
                onClick = onClearAll,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF111827)),
            ) {
                Text("Clear all")
            }
        }
    }
}

@Composable
private fun InsightSection(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Color(0xFF111827), fontSize = 17.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, unit: String, detail: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.height(82.dp), shape = RoundedCornerShape(14.dp), color = Color(0xFFF8FAFC)) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color(0xFF64748B), fontSize = 11.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(value, color = Color(0xFF111827), fontSize = 24.sp, fontWeight = FontWeight.Black, maxLines = 1)
                if (unit.isNotBlank()) Text(unit, color = Color(0xFF64748B), fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(detail, color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SmallPill(title: String, detail: String, color: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.height(58.dp), shape = RoundedCornerShape(14.dp), color = color.toColor().copy(alpha = 0.10f)) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = color.toColor(), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(detail, color = Color(0xFF334155), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SpeedTimeline(points: List<TripTimelinePoint>, events: List<TripEventMarker>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(Color(0xFFF8FAFC), RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        val left = 18f
        val right = size.width - 18f
        val top = 14f
        val bottom = size.height - 20f
        fun x(progress: Int) = left + (right - left) * (progress.coerceIn(0, 100) / 100f)
        fun y(speed: Int): Float {
            val max = 210f
            return bottom - (bottom - top) * (speed.coerceIn(0, 210) / max)
        }
        drawLine(Color(0xFFE2E8F0), Offset(left, bottom), Offset(right, bottom), strokeWidth = 2f)
        val limitPath = Path()
        val speedPath = Path()
        points.forEachIndexed { index, point ->
            val px = x(point.progressPercent)
            val sy = y(point.speedKph)
            val ly = y(point.limitKph)
            if (index == 0) {
                speedPath.moveTo(px, sy)
                limitPath.moveTo(px, ly)
            } else {
                speedPath.lineTo(px, sy)
                limitPath.lineTo(px, ly)
            }
        }
        drawPath(limitPath, Color(0xFF94A3B8), style = Stroke(width = 4f, cap = StrokeCap.Round))
        drawPath(speedPath, Color(0xFF0EA5E9), style = Stroke(width = 6f, cap = StrokeCap.Round))
        events.forEach { event ->
            val color = when (event.severity) {
                TripEventSeverity.INFO -> Color(0xFF38BDF8)
                TripEventSeverity.WARNING -> Color(0xFFF59E0B)
                TripEventSeverity.ALERT -> Color(0xFFEF4444)
            }
            drawCircle(color, radius = 7f, center = Offset(x(event.progressPercent), bottom + 2f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoadSplitBar(types: List<TripRoadType>) {
    val total = types.sumOf { it.km }.coerceAtLeast(1.0)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Color(0xFFE2E8F0), RoundedCornerShape(8.dp)),
    ) {
        types.forEachIndexed { index, type ->
            Box(
                Modifier
                    .weight((type.km / total).toFloat())
                    .height(16.dp)
                    .background(
                        type.color.toColor(),
                        RoundedCornerShape(
                            topStart = if (index == 0) 8.dp else 0.dp,
                            bottomStart = if (index == 0) 8.dp else 0.dp,
                            topEnd = if (index == types.lastIndex) 8.dp else 0.dp,
                            bottomEnd = if (index == types.lastIndex) 8.dp else 0.dp,
                        ),
                    ),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        types.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(8.dp).background(type.color.toColor(), CircleShape))
                Text("${type.label} ${type.km.roundToInt()} km", color = Color(0xFF475569), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun RoadNameRow(road: TripRoadName) {
    MetricLine(road.name, "${road.km.roundToInt()} km")
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF64748B), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, color = Color(0xFF111827), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun TagChip(label: String) {
    Surface(shape = RoundedCornerShape(100.dp), color = Color(0xFFF1F5F9), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0))) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Color(0xFF334155), fontSize = 12.sp)
    }
}

@Composable
private fun RecordChip(label: String) {
    Surface(shape = RoundedCornerShape(100.dp), color = Color(0xFFFFFBEB), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFBBF24))) {
        Text(
            "New · $label",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = Color(0xFF92400E),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ScoreRing(score: Int, label: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 12.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.16f), style = Stroke(width = stroke))
            drawArc(
                color = Color(0xFF5EEAD4),
                startAngle = -90f,
                sweepAngle = score.coerceIn(0, 100) * 3.6f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
            Text(label, color = Color(0xFFB6E3D4), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun Double.oneDecimal(): String = "%.1f".format(this)

private fun String.toColor(): Color =
    runCatching { Color(AndroidColor.parseColor(this)) }.getOrDefault(Color(0xFF2563EB))
