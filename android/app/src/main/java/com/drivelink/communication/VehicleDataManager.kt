package com.drivelink.communication

import com.drivelink.location.LocationSample
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

sealed class PeerEvent {
    data class Status(val sid: String, val vehicleType: String, val sample: LocationSample, val emergencyType: String?) : PeerEvent()
    data class LaneChange(val sid: String, val direction: String, val cancel: Boolean = false) : PeerEvent()
    data class Emergency(val sid: String, val etype: String, val verified: Boolean) : PeerEvent()
    data class Hazard(val sid: String, val kind: String, val lat: Double, val lon: Double) : PeerEvent()
    data class PeerAuthenticated(val sid: String) : PeerEvent()
}

/** Builds signed outgoing messages and validates, authenticates and de-duplicates incoming ones. */
class VehicleDataManager(
    val sessionId: String,
    private val keys: SessionKeys,
    private val auth: VehicleAuthenticationManager,
    private val repo: PeerConnectionRepository,
    private val clock: () -> Long,
    private val onEvent: (PeerEvent) -> Unit,
) {
    private val seq = AtomicLong(0)

    private fun build(type: MessageType, body: String): ByteArray {
        val unsigned = Envelope(type, sessionId, seq.incrementAndGet(), clock() / 1000, body)
        val sig = keys.sign(MessageSerializer.signingInput(unsigned))
        return MessageSerializer.encode(Envelope(type, sessionId, unsigned.seq, unsigned.tsSec, body, sig))
    }

    fun hello(vehicleId: String, vehicleType: String, cred: Pair<String, String>?): ByteArray =
        build(MessageType.HELLO, auth.buildHelloBody(keys, vehicleId, vehicleType, cred))

    fun status(s: LocationSample): ByteArray = build(MessageType.VEHICLE_STATUS,
        JSONObject().put("lat", s.lat).put("lon", s.lon).put("spd", s.speedKmh.coerceIn(0.0, 400.0))
            .put("hdg", s.bearing.coerceIn(0.0, 360.0)).put("acc", s.accuracyM).toString())

    /** [cancel] withdraws an earlier request for the same direction. Advisory only; carries no vehicle-control meaning. */
    fun laneChange(direction: String, cancel: Boolean = false): ByteArray =
        build(MessageType.LANE_CHANGE_REQUEST, JSONObject().put("dir", direction).put("act", if (cancel) "CANCEL" else "REQUEST").toString())

    fun emergency(etype: String): ByteArray =
        build(MessageType.EMERGENCY_ALERT, JSONObject().put("etype", etype).toString())

    fun hazard(kind: String, lat: Double, lon: Double): ByteArray =
        build(MessageType.HAZARD_ALERT, JSONObject().put("kind", kind).put("lat", lat).put("lon", lon).toString())

    fun onBytes(endpointId: String, bytes: ByteArray) {
        val now = clock()
        val peer = repo.get(endpointId) ?: return
        if (!peer.allow(now)) { repo.countRateLimited(); return }
        val env = MessageSerializer.decode(bytes)
        if (env == null) { repo.countInvalid(); return }
        if (kotlin.math.abs(now / 1000 - env.tsSec) > CommunicationProtocol.MAX_CLOCK_SKEW_SEC) { repo.countInvalid(); return } // stale or future

        if (env.type == MessageType.HELLO) {
            val id = auth.verifyHello(env, now)
            if (id == null) { repo.countInvalid(); return }
            if (env.seq <= peer.lastSeq && peer.identity?.sid == env.sid) { repo.countDuplicate(); return }
            peer.identity = id
            peer.lastSeq = env.seq
            peer.lastMessageMs = now
            onEvent(PeerEvent.PeerAuthenticated(id.sid))
            return
        }

        val id = peer.identity
        if (id == null || !auth.verifyMessage(id, env, now)) { repo.countInvalid(); return } // unauthenticated: ignored
        if (env.seq <= peer.lastSeq) { repo.countDuplicate(); return }
        val body = try { JSONObject(env.body) } catch (_: Exception) { repo.countInvalid(); return }
        if (CommunicationProtocol.validateBody(env.type, body) != null) { repo.countInvalid(); return }
        peer.lastSeq = env.seq
        peer.lastMessageMs = now

        val verifiedEmergency = id.credential?.takeIf { it.expSec > now / 1000 }?.etype
        when (env.type) {
            MessageType.VEHICLE_STATUS -> onEvent(PeerEvent.Status(id.sid, id.vehicleType,
                LocationSample(body.getDouble("lat"), body.getDouble("lon"), body.getDouble("spd"),
                    body.getDouble("hdg"), body.getDouble("acc"), now), verifiedEmergency))
            MessageType.LANE_CHANGE_REQUEST -> onEvent(PeerEvent.LaneChange(id.sid, body.getString("dir"), body.optString("act", "REQUEST") == "CANCEL"))
            MessageType.EMERGENCY_ALERT -> {
                val etype = body.getString("etype")
                onEvent(PeerEvent.Emergency(id.sid, etype, verifiedEmergency == etype))
            }
            MessageType.HAZARD_ALERT -> onEvent(PeerEvent.Hazard(id.sid, body.getString("kind"), body.getDouble("lat"), body.getDouble("lon")))
            MessageType.HELLO -> Unit
        }
    }
}
