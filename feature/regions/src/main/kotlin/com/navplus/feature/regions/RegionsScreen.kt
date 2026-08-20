package com.navplus.feature.regions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.navplus.core.regions.model.Region
import com.navplus.core.regions.model.RegionStatus

@Composable
fun RegionsScreen(
    onBack: () -> Unit,
    vm: RegionsViewModel = hiltViewModel(),
) {
    val regions by vm.regions.collectAsStateWithLifecycle()

    Surface(color = Color(0xFF0F172A), modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    "Offline Regions",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Text(
                "Download regions to navigate without internet.",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Spacer(Modifier.height(8.dp))

            if (regions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF3B82F6))
                }
            } else {
                // Group by country code
                val byCountry = regions.groupBy { it.countryCode }
                LazyColumn(Modifier.fillMaxSize()) {
                    byCountry.forEach { (country, regionList) ->
                        item {
                            Text(
                                countryLabel(country),
                                color = Color(0xFF6B7A99),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(regionList) { region ->
                            RegionCard(
                                region = region,
                                onDownload = { vm.download(region.id) },
                                onDelete = { vm.delete(region.id) },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RegionCard(
    region: Region,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(region.name, color = Color.White, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatSize(region.sizeBytes),
                        color = Color(0xFF6B7A99),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.width(12.dp))
                when (region.status) {
                    RegionStatus.AVAILABLE -> Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(36.dp),
                    ) { Text("Download", style = MaterialTheme.typography.labelMedium) }

                    RegionStatus.QUEUED -> Text("Queued", color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.labelMedium)

                    RegionStatus.DOWNLOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF3B82F6),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Downloading…", color = Color(0xFF3B82F6),
                            style = MaterialTheme.typography.labelMedium)
                    }

                    RegionStatus.PROCESSING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFF59E0B),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Processing…", color = Color(0xFFF59E0B),
                            style = MaterialTheme.typography.labelMedium)
                    }

                    RegionStatus.READY -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✓ Ready", color = Color(0xFF10B981), fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete",
                                tint = Color(0xFF6B7A99), modifier = Modifier.size(18.dp))
                        }
                    }

                    RegionStatus.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Failed", color = Color(0xFFEF4444),
                            style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                        ) { Text("Retry", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    val mb = bytes / 1_000_000.0
    return if (gb >= 1) "${"%.1f".format(gb)} GB" else "${"%.0f".format(mb)} MB"
}

private fun countryLabel(code: String) = when (code) {
    "DE" -> "🇩🇪  Germany"
    "AT" -> "🇦🇹  Austria"
    "CH" -> "🇨🇭  Switzerland"
    "FR" -> "🇫🇷  France"
    "IT" -> "🇮🇹  Italy"
    "NL" -> "🇳🇱  Netherlands"
    "BE" -> "🇧🇪  Belgium"
    "PL" -> "🇵🇱  Poland"
    "CZ" -> "🇨🇿  Czechia"
    "ES" -> "🇪🇸  Spain"
    else -> code
}
