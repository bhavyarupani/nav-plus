package com.navplus.core.group.model

import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route

data class GroupSession(
    val code: String,
    val selfId: String,
    val members: Map<String, GroupMember> = emptyMap(),
    val leaderRoute: Route? = null,
    val activeProposal: StopProposal? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
) {
    val isLeader: Boolean get() = members[selfId]?.isLeader == true
    val leader: GroupMember? get() = members.values.firstOrNull { it.isLeader }
    val sortedMembers: List<GroupMember>
        get() = members.values.sortedWith(compareByDescending<GroupMember> { it.isLeader }.thenBy { it.name })
    val groupEtaSec: Long?
        get() = members.values.mapNotNull { it.etaSec }.maxOrNull()
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

data class StopProposal(
    val id: String,
    val options: List<StopOption>,
    val votes: Map<String, StopOptionType> = emptyMap(),
    val isOpen: Boolean = true,
) {
    val winner: StopOption?
        get() = if (!isOpen) {
            val counts = votes.values.groupingBy { it }.eachCount()
            options.maxByOrNull { counts[it.type] ?: 0 }
        } else null
}

data class StopOption(
    val type: StopOptionType,
    val label: String,
    val emoji: String,
)

enum class StopOptionType { FUEL, FOOD, SUPERMARKET, VIEWPOINT, REST, COFFEE }
