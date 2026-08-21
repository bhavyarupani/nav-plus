package com.navplus.core.group.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class WsEnvelope(
    val type: String,
    val payload: String,
)

// ── Outbound ──────────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class JoinPayload(
    val memberId: String,
    val name: String,
    val color: String,
    val isLeader: Boolean,
)

@JsonClass(generateAdapter = true)
data class LocationPayload(
    val memberId: String,
    val lat: Double,
    val lng: Double,
    val bearingDeg: Float,
    val speedKph: Float,
    val etaSec: Long?,
    val distanceRemainingMeters: Double?,
    val hasDeviated: Boolean,
)

@JsonClass(generateAdapter = true)
data class RoutePayload(
    val waypoints: List<LatLngJson>,
    val geometry: List<LatLngJson>,
)

@JsonClass(generateAdapter = true)
data class VoteProposalPayload(
    val proposalId: String,
    val options: List<String>,
)

@JsonClass(generateAdapter = true)
data class VoteCastPayload(
    val proposalId: String,
    val memberId: String,
    val choice: String,
)

@JsonClass(generateAdapter = true)
data class RejoinPayload(
    val memberId: String,
    val distanceMeters: Double,
    val etaSec: Long,
    val locationName: String?,
)

@JsonClass(generateAdapter = true)
data class LatLngJson(val lat: Double, val lng: Double)

object MsgType {
    const val JOIN            = "join"
    const val LEAVE           = "leave"
    const val LOCATION        = "location"
    const val ROUTE           = "route"
    const val VOTE_PROPOSAL   = "vote_proposal"
    const val VOTE_CAST       = "vote_cast"
    const val VOTE_RESULT     = "vote_result"
    const val REJOIN          = "rejoin"
    const val PING            = "ping"
}
