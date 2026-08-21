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

@Singleton
class GroupSyncService @Inject constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<GroupSession?>(null)
    val session: StateFlow<GroupSession?> = _session.asStateFlow()

    private var webSocket: WebSocket? = null
    private val envelopeAdapter = moshi.adapter(WsEnvelope::class.java)

    companion object {
        private const val RELAY_BASE = "wss://97ef9e515cb340.lhr.life/rooms"
        private const val LOCATION_INTERVAL_MS = 4_000L
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
                // Route sync from leader — currently stored in session for follower use
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
        send(MsgType.LOCATION, moshi.adapter(LocationPayload::class.java).toJson(
            LocationPayload(selfId, lat, lng, bearing, speedKph, etaSec, distanceRemainingMeters, hasDeviated)
        ))
    }

    fun proposeStop(options: List<StopOptionType>) {
        val proposalId = UUID.randomUUID().toString()
        send(MsgType.VOTE_PROPOSAL, moshi.adapter(VoteProposalPayload::class.java).toJson(
            VoteProposalPayload(proposalId, options.map { it.name })
        ))
    }

    fun castVote(proposalId: String, choice: StopOptionType) {
        val selfId = _session.value?.selfId ?: return
        send(MsgType.VOTE_CAST, moshi.adapter(VoteCastPayload::class.java).toJson(
            VoteCastPayload(proposalId, selfId, choice.name)
        ))
    }

    fun closeVoting() = send(MsgType.VOTE_RESULT, "\"closed\"")

    fun broadcastRejoin(memberId: String, distanceMeters: Double, etaSec: Long, locationName: String?) {
        send(MsgType.REJOIN, moshi.adapter(RejoinPayload::class.java).toJson(
            RejoinPayload(memberId, distanceMeters, etaSec, locationName)
        ))
    }

    fun disconnect() {
        webSocket?.close(1000, "user left")
        webSocket = null
        _session.value = null
    }

    private fun send(type: String, payload: String) {
        val json = envelopeAdapter.toJson(WsEnvelope(type, payload))
        webSocket?.send(json)
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
