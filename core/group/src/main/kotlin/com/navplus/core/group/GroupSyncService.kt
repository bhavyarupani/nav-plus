package com.navplus.core.group

import com.navplus.core.group.model.ConnectionState
import com.navplus.core.group.model.GroupMember
import com.navplus.core.group.model.GroupSession
import com.navplus.core.group.model.JoinPayload
import com.navplus.core.group.model.LatLngJson
import com.navplus.core.group.model.LocationPayload
import com.navplus.core.group.model.MsgType
import com.navplus.core.group.model.RejoinInfo
import com.navplus.core.group.model.RejoinPayload
import com.navplus.core.group.model.RoutePayload
import com.navplus.core.group.model.StopOption
import com.navplus.core.group.model.StopOptionType
import com.navplus.core.group.model.StopProposal
import com.navplus.core.group.model.VoteCastPayload
import com.navplus.core.group.model.VoteProposalPayload
import com.navplus.core.group.model.WsEnvelope
import com.navplus.core.common.model.LatLng
import com.navplus.core.common.model.Route
import com.navplus.core.common.model.bearingTo
import com.navplus.core.common.model.distanceTo
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.roundToLong

@Singleton
class GroupSyncService @Inject constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<GroupSession?>(null)
    val session: StateFlow<GroupSession?> = _session.asStateFlow()

    private var webSocket: WebSocket? = null
    private var debugConvoyRoute: Route? = null
    private val envelopeAdapter = moshi.adapter(WsEnvelope::class.java)

    companion object {
        private const val RELAY_BASE = "wss://97ef9e515cb340.lhr.life/rooms"
        private const val LOCATION_INTERVAL_MS = 4_000L
        private const val DEBUG_ROOM_CODE = "SIM-3"
        private const val DEBUG_SELF_ID = "debug-driver"
        private const val DEBUG_TRAIL_ID = "debug-trail"
        private const val DEBUG_SWEEP_ID = "debug-sweep"
    }

    fun createSession(selfName: String, selfColor: String): String {
        val code = UUID.randomUUID().toString().take(6).uppercase()
        val selfId = UUID.randomUUID().toString()
        connect(code, selfId, selfName, selfColor, isLeader = true)
        return code
    }

    fun joinSession(code: String, selfName: String, selfColor: String) {
        val selfId = UUID.randomUUID().toString()
        connect(code, selfId, selfName, selfColor, isLeader = false)
    }

    private fun connect(code: String, selfId: String, name: String, color: String, isLeader: Boolean) {
        debugConvoyRoute = null
        _session.value = GroupSession(
            code = code,
            selfId = selfId,
            members = mapOf(selfId to GroupMember(selfId, name, color, isLeader, isOnline = true)),
            connectionState = ConnectionState.CONNECTING,
        )
        val request = Request.Builder().url("$RELAY_BASE/$code").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _session.update { it?.copy(connectionState = ConnectionState.CONNECTED) }
                send(MsgType.JOIN, moshi.adapter(JoinPayload::class.java).toJson(
                    JoinPayload(selfId, name, color, isLeader)
                ))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val envelope = envelopeAdapter.fromJson(text) ?: return
                handleMessage(envelope)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                _session.update { it?.copy(connectionState = ConnectionState.DISCONNECTED) }
                scope.launch { delay(5_000); reconnect() }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _session.update { it?.copy(connectionState = ConnectionState.DISCONNECTED) }
            }
        })
    }

    private fun reconnect() {
        val s = _session.value ?: return
        val self = s.members[s.selfId] ?: return
        connect(s.code, s.selfId, self.name, self.color, self.isLeader)
    }

    private fun handleMessage(envelope: WsEnvelope) {
        when (envelope.type) {
            MsgType.JOIN -> {
                val p = moshi.adapter(JoinPayload::class.java).fromJson(envelope.payload) ?: return
                _session.update { s ->
                    s?.copy(members = s.members + (p.memberId to GroupMember(
                        id = p.memberId, name = p.name, color = p.color,
                        isLeader = p.isLeader, isOnline = true, lastSeenMs = System.currentTimeMillis(),
                    )))
                }
            }
            MsgType.LEAVE -> {
                val memberId = envelope.payload.trim('"')
                _session.update { s ->
                    s?.copy(members = s.members.mapValues { (id, m) ->
                        if (id == memberId) m.copy(isOnline = false) else m
                    })
                }
            }
            MsgType.LOCATION -> {
                val p = moshi.adapter(LocationPayload::class.java).fromJson(envelope.payload) ?: return
                _session.update { s ->
                    s?.copy(members = s.members.mapValues { (id, m) ->
                        if (id == p.memberId) m.copy(
                            location = LatLng(p.lat, p.lng),
                            bearingDeg = p.bearingDeg,
                            speedKph = p.speedKph,
                            etaSec = p.etaSec,
                            distanceRemainingMeters = p.distanceRemainingMeters,
                            hasDeviated = p.hasDeviated,
                            lastSeenMs = System.currentTimeMillis(),
                            isOnline = true,
                        ) else m
                    })
                }
            }
            MsgType.ROUTE -> {
                val p = moshi.adapter(RoutePayload::class.java).fromJson(envelope.payload) ?: return
                _session.update { s -> s?.copy(leaderRoute = p.toRoute()) }
            }
            MsgType.VOTE_PROPOSAL -> {
                val p = moshi.adapter(VoteProposalPayload::class.java).fromJson(envelope.payload) ?: return
                val options = p.options.mapNotNull { key ->
                    STOP_OPTIONS.find { it.type.name == key }
                }
                _session.update { s ->
                    s?.copy(activeProposal = StopProposal(p.proposalId, options, isOpen = true))
                }
            }
            MsgType.VOTE_CAST -> {
                val p = moshi.adapter(VoteCastPayload::class.java).fromJson(envelope.payload) ?: return
                val optionType = StopOptionType.entries.firstOrNull { it.name == p.choice } ?: return
                _session.update { s ->
                    val proposal = s?.activeProposal ?: return@update s
                    if (proposal.id != p.proposalId) return@update s
                    s.copy(activeProposal = proposal.copy(votes = proposal.votes + (p.memberId to optionType)))
                }
            }
            MsgType.VOTE_RESULT -> {
                _session.update { s -> s?.copy(activeProposal = s.activeProposal?.copy(isOpen = false)) }
            }
            MsgType.REJOIN -> {
                val p = moshi.adapter(RejoinPayload::class.java).fromJson(envelope.payload) ?: return
                _session.update { s ->
                    s?.copy(members = s.members.mapValues { (id, m) ->
                        if (id == p.memberId) m.copy(
                            hasDeviated = true,
                            rejoinInfo = RejoinInfo(p.distanceMeters, p.etaSec, p.locationName),
                        ) else m
                    })
                }
            }
        }
    }

    fun broadcastLocation(
        lat: Double, lng: Double, bearing: Float, speedKph: Float,
        etaSec: Long?, distanceRemainingMeters: Double?, hasDeviated: Boolean,
    ) {
        val selfId = _session.value?.selfId ?: return
        val now = System.currentTimeMillis()
        _session.update { s ->
            s?.copy(members = s.members.mapValues { (id, member) ->
                if (id == selfId) member.copy(
                    location = LatLng(lat, lng),
                    bearingDeg = bearing,
                    speedKph = speedKph,
                    etaSec = etaSec,
                    distanceRemainingMeters = distanceRemainingMeters,
                    hasDeviated = hasDeviated,
                    rejoinInfo = if (hasDeviated) member.rejoinInfo else null,
                    lastSeenMs = now,
                    isOnline = true,
                ) else member
            })
        }
        debugConvoyRoute?.let { route ->
            updateDebugPeers(route, distanceRemainingMeters ?: route.distanceMeters, speedKph, now)
        }
        send(MsgType.LOCATION, moshi.adapter(LocationPayload::class.java).toJson(
            LocationPayload(selfId, lat, lng, bearing, speedKph, etaSec, distanceRemainingMeters, hasDeviated)
        ))
    }

    fun broadcastRoute(route: Route) {
        _session.update { s -> s?.copy(leaderRoute = route) }
        val payload = RoutePayload(
            waypoints = route.waypoints.map { LatLngJson(it.lat, it.lng) },
            geometry = route.geometry.map { LatLngJson(it.lat, it.lng) },
        )
        send(MsgType.ROUTE, moshi.adapter(RoutePayload::class.java).toJson(payload))
    }

    fun startDebugConvoy(route: Route) {
        webSocket?.close(1000, "debug convoy")
        webSocket = null
        debugConvoyRoute = route
        val now = System.currentTimeMillis()
        _session.value = GroupSession(
            code = DEBUG_ROOM_CODE,
            selfId = DEBUG_SELF_ID,
            leaderRoute = route,
            connectionState = ConnectionState.CONNECTED,
            members = debugMembers(route, distanceRemainingMeters = route.distanceMeters, selfSpeedKph = 45f, nowMs = now),
        )
    }

    fun proposeStop(options: List<StopOptionType>) {
        val proposalId = UUID.randomUUID().toString()
        val stopOptions = options.mapNotNull { type ->
            STOP_OPTIONS.find { it.type == type }
        }
        _session.update { s ->
            s?.copy(activeProposal = StopProposal(proposalId, stopOptions, isOpen = true))
        }
        send(MsgType.VOTE_PROPOSAL, moshi.adapter(VoteProposalPayload::class.java).toJson(
            VoteProposalPayload(proposalId, options.map { it.name })
        ))
    }

    fun castVote(proposalId: String, choice: StopOptionType) {
        val selfId = _session.value?.selfId ?: return
        _session.update { s ->
            val proposal = s?.activeProposal ?: return@update s
            if (proposal.id != proposalId) return@update s
            s.copy(activeProposal = proposal.copy(votes = proposal.votes + (selfId to choice)))
        }
        send(MsgType.VOTE_CAST, moshi.adapter(VoteCastPayload::class.java).toJson(
            VoteCastPayload(proposalId, selfId, choice.name)
        ))
    }

    fun closeVoting() {
        _session.update { s -> s?.copy(activeProposal = s.activeProposal?.copy(isOpen = false)) }
        send(MsgType.VOTE_RESULT, "\"closed\"")
    }

    fun broadcastRejoin(memberId: String, distanceMeters: Double, etaSec: Long, locationName: String?) {
        send(MsgType.REJOIN, moshi.adapter(RejoinPayload::class.java).toJson(
            RejoinPayload(memberId, distanceMeters, etaSec, locationName)
        ))
    }

    fun disconnect() {
        webSocket?.close(1000, "user left")
        webSocket = null
        debugConvoyRoute = null
        _session.value = null
    }

    private fun send(type: String, payload: String) {
        val json = envelopeAdapter.toJson(WsEnvelope(type, payload))
        webSocket?.send(json)
    }

    private fun updateDebugPeers(
        route: Route,
        distanceRemainingMeters: Double,
        selfSpeedKph: Float,
        nowMs: Long,
    ) {
        val debugMembers = debugMembers(route, distanceRemainingMeters, selfSpeedKph, nowMs)
        _session.update { s ->
            if (s?.code != DEBUG_ROOM_CODE) return@update s
            s.copy(members = s.members + debugMembers.filterKeys { it != s.selfId })
        }
    }

    private fun debugMembers(
        route: Route,
        distanceRemainingMeters: Double,
        selfSpeedKph: Float,
        nowMs: Long,
    ): Map<String, GroupMember> {
        val total = route.distanceMeters.coerceAtLeast(1.0)
        val selfDistanceFromStart = (total - distanceRemainingMeters).coerceIn(0.0, total)
        val selfFix = route.pointAtDistance(selfDistanceFromStart)
        val trailDistanceFromStart = (selfDistanceFromStart - 260.0).coerceAtLeast(0.0)
        val trailFix = route.pointAtDistance(trailDistanceFromStart)

        val sweepDeviated = selfDistanceFromStart in (total * 0.38)..(total * 0.78)
        val sweepRouteDistance = (selfDistanceFromStart - 520.0).coerceIn(0.0, total)
        val sweepFix = route.pointAtDistance(sweepRouteDistance)
        val sweepLocation = if (sweepDeviated) {
            sweepFix.point.offsetMeters(north = -55.0, east = 190.0)
        } else {
            sweepFix.point
        }
        val sweepRejoin = if (sweepDeviated) {
            RejoinInfo(
                distanceMeters = 430.0,
                etaSec = 55L,
                locationName = "next safe rejoin",
            )
        } else null

        return mapOf(
            DEBUG_SELF_ID to GroupMember(
                id = DEBUG_SELF_ID,
                name = "You",
                color = "#38BDF8",
                isLeader = true,
                location = selfFix.point,
                bearingDeg = selfFix.bearingDeg,
                speedKph = selfSpeedKph,
                etaSec = etaFrom(distanceRemainingMeters, selfSpeedKph),
                distanceRemainingMeters = distanceRemainingMeters,
                lastSeenMs = nowMs,
                isOnline = true,
            ),
            DEBUG_TRAIL_ID to GroupMember(
                id = DEBUG_TRAIL_ID,
                name = "Mia",
                color = "#22C55E",
                location = trailFix.point,
                bearingDeg = trailFix.bearingDeg,
                speedKph = (selfSpeedKph - 6f).coerceAtLeast(22f),
                etaSec = etaFrom((total - trailDistanceFromStart).coerceAtLeast(0.0), (selfSpeedKph - 6f).coerceAtLeast(22f)),
                distanceRemainingMeters = (total - trailDistanceFromStart).coerceAtLeast(0.0),
                lastSeenMs = nowMs,
                isOnline = true,
            ),
            DEBUG_SWEEP_ID to GroupMember(
                id = DEBUG_SWEEP_ID,
                name = "Arun",
                color = "#F97316",
                location = sweepLocation,
                bearingDeg = sweepFix.bearingDeg,
                speedKph = if (sweepDeviated) 28f else (selfSpeedKph - 10f).coerceAtLeast(20f),
                etaSec = etaFrom((total - sweepRouteDistance + if (sweepDeviated) 430.0 else 0.0).coerceAtLeast(0.0), if (sweepDeviated) 28f else (selfSpeedKph - 10f).coerceAtLeast(20f)),
                distanceRemainingMeters = (total - sweepRouteDistance + if (sweepDeviated) 430.0 else 0.0).coerceAtLeast(0.0),
                lastSeenMs = nowMs,
                isOnline = true,
                hasDeviated = sweepDeviated,
                rejoinInfo = sweepRejoin,
            ),
        )
    }

    private fun etaFrom(distanceMeters: Double, speedKph: Float): Long {
        val speedMps = (speedKph / 3.6f).takeIf { it > 1f } ?: 12.5f
        return (distanceMeters / speedMps).roundToLong()
    }

    private val STOP_OPTIONS = listOf(
        StopOption(StopOptionType.FUEL,        "Fuel",        "⛽"),
        StopOption(StopOptionType.FOOD,        "Food",        "🍔"),
        StopOption(StopOptionType.SUPERMARKET, "Supermarket", "🛒"),
        StopOption(StopOptionType.VIEWPOINT,   "Viewpoint",   "🏞"),
        StopOption(StopOptionType.REST,        "Rest area",   "🚻"),
        StopOption(StopOptionType.COFFEE,      "Coffee",      "☕"),
    )
}

private data class RouteFix(val point: LatLng, val bearingDeg: Float)

private fun RoutePayload.toRoute(): Route {
    val routeGeometry = geometry.map { LatLng(it.lat, it.lng) }
    val routeWaypoints = waypoints.map { LatLng(it.lat, it.lng) }
    val distance = routeGeometry.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }
    return Route(
        id = "synced-${System.currentTimeMillis()}",
        waypoints = routeWaypoints.ifEmpty {
            listOfNotNull(routeGeometry.firstOrNull(), routeGeometry.lastOrNull())
        },
        geometry = routeGeometry,
        steps = emptyList(),
        distanceMeters = distance,
        durationSeconds = (distance / 13.9).roundToLong(),
    )
}

private fun Route.pointAtDistance(distanceMeters: Double): RouteFix {
    if (geometry.size < 2) return RouteFix(geometry.firstOrNull() ?: LatLng.ZERO, 0f)
    var remaining = distanceMeters.coerceAtLeast(0.0)
    for (index in 0 until geometry.lastIndex) {
        val start = geometry[index]
        val end = geometry[index + 1]
        val segmentDistance = start.distanceTo(end)
        if (segmentDistance <= 0.0) continue
        if (remaining <= segmentDistance) {
            val fraction = remaining / segmentDistance
            return RouteFix(
                point = start.interpolate(end, fraction),
                bearingDeg = start.bearingTo(end).toFloat(),
            )
        }
        remaining -= segmentDistance
    }
    val lastSegmentStart = geometry[geometry.lastIndex - 1]
    val last = geometry.last()
    return RouteFix(last, lastSegmentStart.bearingTo(last).toFloat())
}

private fun LatLng.interpolate(end: LatLng, fraction: Double): LatLng = LatLng(
    lat = lat + (end.lat - lat) * fraction.coerceIn(0.0, 1.0),
    lng = lng + (end.lng - lng) * fraction.coerceIn(0.0, 1.0),
)

private fun LatLng.offsetMeters(north: Double, east: Double): LatLng {
    val latMeters = 111_320.0
    val lngMeters = latMeters * cos(Math.toRadians(lat))
    return LatLng(
        lat = lat + north / latMeters,
        lng = lng + east / lngMeters,
    )
}
