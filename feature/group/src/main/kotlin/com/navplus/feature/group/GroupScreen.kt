package com.navplus.feature.group

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.navplus.core.group.model.ConnectionState
import com.navplus.core.group.model.GroupMember
import com.navplus.core.group.model.GroupSession
import com.navplus.core.group.model.StopOptionType

@Composable
fun GroupScreen(
    onLeave: () -> Unit,
    vm: GroupViewModel = hiltViewModel(),
) {
    val session by vm.session.collectAsStateWithLifecycle()
    var showVoting by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color(0xFF0F172A))) {
        if (session == null) {
            GroupLobbyScreen(onSessionStarted = {})
        } else {
            Column(Modifier.fillMaxSize()) {
                GroupHeader(session!!, onLeave = { vm.leaveGroup(); onLeave() })
                EtaBoard(session!!)
                MemberList(session!!)

                if (session!!.isLeader) {
                    Spacer(Modifier.weight(1f))
                    GroupLeaderActions(
                        onProposeStop = {
                            vm.proposeStop(DEFAULT_STOP_OPTIONS)
                            showVoting = true
                        },
                    )
                }
            }

            session?.activeProposal?.let { proposal ->
                AnimatedVisibility(
                    visible = showVoting || proposal.isOpen,
                    enter = slideInVertically { it },
                    exit = slideOutVertically { it },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        VotingBottomSheet(
                            proposal = proposal,
                            session = session!!,
                            onVote = { choice ->
                                vm.castVote(proposal.id, choice)
                                showVoting = false
                            },
                            onClose = { vm.closeVoting(); showVoting = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(session: GroupSession, onLeave: () -> Unit) {
    val connected = session.connectionState == ConnectionState.CONNECTED
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(10.dp).background(
                if (connected) Color(0xFF10B981) else Color(0xFFEF4444), CircleShape
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Room ${session.code}",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${session.members.size} cars",
            color = Color(0xFF94A3B8),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onLeave) {
            Icon(Icons.Rounded.Close, contentDescription = "Leave", tint = Color(0xFF94A3B8))
        }
    }
}

@Composable
private fun EtaBoard(session: GroupSession) {
    val groupEta = session.groupEtaSec ?: return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Group arrival", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            session.sortedMembers.forEach { member ->
                MemberEtaRow(member, isSelf = member.id == session.selfId)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Group ETA", color = Color(0xFF6B7A99), style = MaterialTheme.typography.labelMedium)
                Text(
                    formatEta(groupEta),
                    color = Color(0xFFF59E0B),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MemberEtaRow(member: GroupMember, isSelf: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(
                color = runCatching { Color(android.graphics.Color.parseColor(member.color)) }
                    .getOrDefault(Color(0xFF3B82F6)),
                shape = CircleShape,
            ))
            Spacer(Modifier.width(8.dp))
            Text(
                if (isSelf) "${member.name} (you)" else member.name,
                color = if (isSelf) Color.White else Color(0xFFB0B8CC),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (member.isLeader) {
                Spacer(Modifier.width(4.dp))
                Text("👑", fontSize = 12.sp)
            }
            if (member.hasDeviated) {
                Spacer(Modifier.width(4.dp))
                Text("↩", color = Color(0xFFF59E0B), fontSize = 12.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                member.etaSec?.let { formatEta(it) } ?: "–",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
            )
            member.rejoinInfo?.let { rejoin ->
                Text(
                    "rejoins in ${formatDist(rejoin.distanceMeters)}",
                    color = Color(0xFFF59E0B),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun MemberList(session: GroupSession) {
    LazyColumn(Modifier.padding(horizontal = 16.dp)) {
        item {
            Text(
                "Convoy",
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        items(session.sortedMembers) { member ->
            ConvoyMemberCard(member, isSelf = member.id == session.selfId)
        }
    }
}

@Composable
private fun ConvoyMemberCard(member: GroupMember, isSelf: Boolean) {
    val memberColor = runCatching { Color(android.graphics.Color.parseColor(member.color)) }
        .getOrDefault(Color(0xFF3B82F6))
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(if (isSelf) Modifier.border(1.dp, memberColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp)) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C2E)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(memberColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(member.name.firstOrNull()?.toString() ?: "?", color = memberColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(member.name, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                val distStr = member.distanceRemainingMeters?.let { formatDist(it) }
                Text(
                    buildString {
                        if (member.speedKph > 0) append("${member.speedKph.toInt()} km/h")
                        if (distStr != null) append(" · $distStr")
                    }.ifBlank { if (member.isOnline) "Connected" else "Offline" },
                    color = Color(0xFF6B7A99),
                    style = MaterialTheme.typography.labelMedium,
                )
                val rejoin = member.rejoinInfo
                if (member.hasDeviated && rejoin != null) {
                    Text(
                        "↩ Rejoins in ${formatDist(rejoin.distanceMeters)} · +${formatEta(rejoin.etaSec)}",
                        color = Color(0xFFF59E0B),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (!member.isOnline) {
                Box(Modifier.size(8.dp).background(Color(0xFFEF4444), CircleShape))
            } else {
                Box(Modifier.size(8.dp).background(Color(0xFF10B981), CircleShape))
            }
        }
    }
}

@Composable
private fun GroupLeaderActions(onProposeStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onProposeStop) {
            Text("📍 Vote on next stop", color = Color(0xFF3B82F6), fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatEta(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatDist(meters: Double): String = when {
    meters >= 1_000 -> "${"%.0f".format(meters / 1_000)} km"
    else -> "${meters.toInt()} m"
}

private val DEFAULT_STOP_OPTIONS = listOf(
    StopOptionType.FUEL,
    StopOptionType.FOOD,
    StopOptionType.SUPERMARKET,
    StopOptionType.REST,
    StopOptionType.COFFEE,
)
