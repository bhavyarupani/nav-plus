package com.navplus.feature.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.navplus.core.group.model.GroupSession
import com.navplus.core.group.model.StopOption
import com.navplus.core.group.model.StopOptionType
import com.navplus.core.group.model.StopProposal

@Composable
fun VotingBottomSheet(
    proposal: StopProposal,
    session: GroupSession,
    onVote: (StopOptionType) -> Unit,
    onClose: () -> Unit,
) {
    val selfVote = proposal.votes[session.selfId]
    val totalVotes = proposal.votes.size
    val voteCounts = proposal.votes.values.groupingBy { it }.eachCount()

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = Color(0xFF1C1C2E),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Next stop?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1f))
                Text("$totalVotes/${session.members.size} voted", color = Color(0xFF94A3B8), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))

            proposal.options.forEach { option ->
                val votes = voteCounts[option.type] ?: 0
                val fraction = if (totalVotes > 0) votes.toFloat() / totalVotes else 0f
                val isMyVote = selfVote == option.type
                val isWinner = proposal.winner?.type == option.type

                VoteOption(
                    option = option,
                    votes = votes,
                    fraction = fraction,
                    isMyVote = isMyVote,
                    isWinner = isWinner,
                    canVote = selfVote == null && proposal.isOpen,
                    onClick = { onVote(option.type) },
                )
                Spacer(Modifier.height(8.dp))
            }

            if (!proposal.isOpen) {
                proposal.winner?.let { winner ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${winner.emoji} Routing to ${winner.label}…",
                        color = Color(0xFF10B981),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (session.isLeader && proposal.isOpen) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Close voting", color = Color(0xFF3B82F6))
                }
            }
        }
    }
}

@Composable
private fun VoteOption(
    option: StopOption,
    votes: Int,
    fraction: Float,
    isMyVote: Boolean,
    isWinner: Boolean,
    canVote: Boolean,
    onClick: () -> Unit,
) {
    val bg = when {
        isWinner  -> Color(0xFF10B981).copy(alpha = 0.15f)
        isMyVote  -> Color(0xFF3B82F6).copy(alpha = 0.15f)
        else      -> Color(0xFF2D2D44)
    }
    val border = when {
        isWinner  -> Color(0xFF10B981)
        isMyVote  -> Color(0xFF3B82F6)
        else      -> Color.Transparent
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (canVote) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(option.emoji, fontSize = 22.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    option.label,
                    color = Color.White,
                    fontWeight = if (isMyVote || isWinner) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$votes",
                    color = if (isWinner) Color(0xFF10B981) else Color(0xFF94A3B8),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (fraction > 0f) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = if (isWinner) Color(0xFF10B981) else Color(0xFF3B82F6),
                    trackColor = Color(0xFF374151),
                )
            }
        }
    }
}
