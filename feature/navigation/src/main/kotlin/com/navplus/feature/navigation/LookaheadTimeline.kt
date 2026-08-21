package com.navplus.feature.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navplus.core.navigation.LookaheadEvent
import com.navplus.core.navigation.LookaheadSeverity
import com.navplus.core.navigation.RoadCharacter
import com.navplus.core.navigation.emoji

@Composable
fun LookaheadTimeline(
    events: List<LookaheadEvent>,
    roadCharacters: List<RoadCharacter>,
    modifier: Modifier = Modifier,
) {
    if (events.isEmpty() && roadCharacters.isEmpty()) return

    Column(modifier) {
        if (roadCharacters.isNotEmpty()) {
            RoadPersonalityRow(roadCharacters)
        }
        if (events.isNotEmpty()) {
            TimelineRow(events)
        }
    }
}

@Composable
private fun RoadPersonalityRow(characters: List<RoadCharacter>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(characters) { char ->
            RoadPersonalityChip(char)
        }
    }
}

@Composable
private fun RoadPersonalityChip(char: RoadCharacter) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1C1C2E).copy(alpha = 0.92f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(char.type.emoji(), fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text(
                    char.roadName,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    char.description,
                    color = Color(0xFF94A3B8),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(events: List<LookaheadEvent>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(events.take(8)) { event ->
            TimelineEventChip(event)
        }
    }
}

@Composable
private fun TimelineEventChip(event: LookaheadEvent) {
    val accent = when (event.severity) {
        LookaheadSeverity.ALERT   -> Color(0xFFEF4444)
        LookaheadSeverity.WARNING -> Color(0xFFF59E0B)
        LookaheadSeverity.INFO    -> Color(0xFF3B82F6)
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF1C1C2E).copy(alpha = 0.92f),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(6.dp).background(accent, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(event.emoji, fontSize = 14.sp)
            Spacer(Modifier.width(4.dp))
            Column {
                Text(
                    formatDistance(event.distanceMeters),
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    event.title,
                    color = Color(0xFFB0B8CC),
                    style = MaterialTheme.typography.labelSmall,
                )
                event.subtitle?.let { subtitle ->
                    Text(
                        subtitle,
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

private fun formatDistance(meters: Double): String = when {
    meters >= 1_000 -> "${"%.0f".format(meters / 1_000)} km"
    else -> "${meters.toInt()} m"
}
