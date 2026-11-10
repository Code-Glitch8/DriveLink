package com.drivelink.driving

import android.content.Context
import com.drivelink.authentication.SupabaseApi
import com.drivelink.communication.CommunicationProtocol
import com.drivelink.communication.CredentialVerifier
import com.drivelink.communication.DeviceDiscoveryManager
import com.drivelink.communication.NearbyConnectionManager
import com.drivelink.communication.PeerConnectionRepository
import com.drivelink.communication.PeerDelegate
import com.drivelink.communication.PeerEvent
import com.drivelink.communication.SessionKeys
import com.drivelink.communication.VehicleAuthenticationManager
import com.drivelink.communication.VehicleDataManager
import com.drivelink.config.AppConfig
import com.drivelink.location.LocationProvider
import com.drivelink.location.LocationSample
import com.drivelink.location.SpeedFilter
import com.drivelink.location.SpeedFix
import com.drivelink.location.SpeedReading
import com.drivelink.orientation.HeadingTracker
import com.drivelink.location.VehicleTracker
import com.drivelink.notifications.AlertSpeaker
import com.drivelink.safety.RiskAssessment
import com.drivelink.safety.RiskEngine
import com.drivelink.safety.RiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

data class PeerView(
    val sid: String,
    val vehicleType: String,
    val distanceM: Double,
    val direction: String,
    val ageMs: Long,
    val lat: Double,
    val lon: Double,
    val risk: RiskAssessment,
    val verifiedEmergency: String?,
    val bearing: Double = 0.0,   // peer heading in degrees, used by the map direction arrow
    val speedKmh: Double = 0.0,
)

/** A recent lane-merge request from a peer, keyed by its temporary session id. Shown briefly on the map. */
data class LaneRequest(val direction: String, val atMs: Long)

/** A merge request from another vehicle that is relevant to me. [side] is where it is relative to MY heading. */
data class MergeAlert(val sid: String, val direction: String, val uncertain: Boolean, val side: String, val atMs: Long) {
    val label: String get() = "…" + sid.takeLast(4)
}

/** My own outgoing merge signal, kept so the driver can cancel it. */
data class MyMerge(val direction: String, val atMs: Long, val recipients: List<String>, val recipientCount: Int)

/** level: 0 info, 1 caution, 2 high, 3 emergency */
data class AlertItem(val id: Long, val text: String, val level: Int, val atMs: Long)

data class JourneyState(
    val running: Boolean = false,
    val self: LocationSample? = null,
    val peers: List<PeerView> = emptyList(),
    val connected: Int = 0,
    val comm: String = "Idle",
    val alerts: List<AlertItem> = emptyList(),
    val dataQualityWarning: String? = null,
    val canSendEmergency: Boolean = false,
    val sessionId: String = "",
    val laneRequests: Map<String, LaneRequest> = emptyMap(),
    val mergeAlerts: List<MergeAlert> = emptyList(),
    val myMerge: MyMerge? = null,
    /** Smoothed speed for display; [SpeedReading.Unavailable] when no trustworthy recent fix exists. */
    val speed: SpeedReading = SpeedReading.Unavailable,
)

data class JourneyConfig(
    val vehicleId: String?,
    val driveLinkId: String,
    val vehicleType: String,
    val accessToken: String?,
    val voiceAlerts: Boolean,
)

/**
 * Orchestrates a journey: location -> signed status broadcast over Nearby -> tracker -> risk engine -> alerts.
 * Runs inside JourneyService. Needs no internet once peers are connected.
 */
object JourneyManager {
    private val _state = MutableStateFlow(JourneyState())
    val state: StateFlow<JourneyState> = _state.asStateFlow()

    private var config = JourneyConfig(null, "UNREGISTERED", "car", null, true)
    private var scope: CoroutineScope? = null
    private var location: LocationProvider? = null
    private var discovery: DeviceDiscoveryManager? = null
    private var nearby: NearbyConnectionManager? = null
    private var data: VehicleDataManager? = null
    private var speaker: AlertSpeaker? = null
    private val tracker = VehicleTracker()
    private val repo = PeerConnectionRepository()
    private val alertIds = AtomicLong(0)
    @Volatile private var me: LocationSample? = null
    @Volatile private var credential: Pair<String, String>? = null
    private val speedHistory = ArrayDeque<Pair<Long, Double>>()
    private val speedFilter = SpeedFilter()
    private var appContext: Context? = null
    private var lastBrakeSentMs = 0L

    fun configure(c: JourneyConfig) { config = c }
    val isRunning: Boolean get() = _state.value.running

    fun start(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        val sidName = CommunicationProtocol.NAME_PREFIX + ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val keys = SessionKeys()
        val verifier = CredentialVerifier(AppConfig.credentialPublicKey)
        val auth = VehicleAuthenticationManager(verifier)
        val clock = { System.currentTimeMillis() }
        repo.clear(); tracker.clear(); credential = null; me = null; synchronized(speedHistory) { speedHistory.clear() }
        synchronized(speedFilter) { speedFilter.reset() }
        appContext = app

        val dm = VehicleDataManager(sidName, keys, auth, repo, clock) { handleEvent(it) }
        val nc = NearbyConnectionManager(app)
        val dd = DeviceDiscoveryManager(nc, repo, sidName, clock)
        data = dm; nearby = nc; discovery = dd
        speaker = AlertSpeaker(app).also { it.enabled = config.voiceAlerts }

        dd.delegate = object : PeerDelegate {
            override fun onPeerConnected(endpointId: String) {
                nc.send(listOf(endpointId), dm.hello(config.driveLinkId, config.vehicleType, credential))
            }
            override fun onPeerDisconnected(endpointId: String, sid: String?) { sid?.let { tracker.remove(it) } }
            override fun onBytes(endpointId: String, bytes: ByteArray) { dm.onBytes(endpointId, bytes) }
            override fun onStatus(message: String) { _state.update { it.copy(comm = message) } }
        }

        _state.value = JourneyState(running = true, comm = "Searching for nearby DriveLink vehicles…", sessionId = sidName)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc
        val lp = LocationProvider(app)
        location = lp
        HeadingTracker.acquire(app)
        lp.start { raw -> onLocation(raw) }
        dd.start()

        // Emergency vehicles fetch a server-signed credential (needs internet once); peers verify it offline.
        val token = config.accessToken
        val vid = config.vehicleId
        if (token != null && vid != null && config.vehicleType in CommunicationProtocol.EMERGENCY_TYPES) {
            sc.launch {
                try {
                    credential = SupabaseApi.issueCredential(token, vid)
                    _state.update { it.copy(canSendEmergency = true) }
                    broadcastHello()
                } catch (e: Exception) {
                    pushAlert("Emergency credential unavailable: ${e.message}", 0)
                }
            }
        }

        sc.launch {
            var n = 0
            while (isActive) {
                tick()
                if (++n % 60 == 0) broadcastHello()
                delay(1000)
            }
        }
    }

    fun stop() {
        scope?.cancel(); scope = null
        if (location != null) HeadingTracker.release() // pairs with acquire() in start()
        location?.stop(); location = null
        discovery?.stop(); discovery = null
        nearby = null; data = null
        speaker?.shutdown(); speaker = null
        tracker.clear(); repo.clear(); me = null
        _state.value = JourneyState()
    }

    private fun broadcastHello() {
        val dm = data ?: return
        nearby?.send(repo.connectedIds(), dm.hello(config.driveLinkId, config.vehicleType, credential))
    }

    private fun onLocation(raw: LocationSample) {
        HeadingTracker.onGps(raw)
        // Share/assess with the fused heading: compass when stationary, GPS course when moving.
        val s = HeadingTracker.current()?.let { raw.copy(bearing = it) } ?: raw
        me = s
        noteSpeed(s)
        synchronized(speedFilter) {
            speedFilter.accept(SpeedFix(raw.hasSpeed, raw.speedKmh / 3.6, raw.accuracyM, raw.speedAccuracyMps, raw.fixTimeMs, raw.fixAgeMs), System.currentTimeMillis())
        }
    }

    private const val MERGE_TTL_MS = 8_000L

    private fun noteSpeed(s: LocationSample) {
        synchronized(speedHistory) {
            speedHistory.addLast(s.timestampMs to s.speedKmh)
            while (speedHistory.isNotEmpty() && s.timestampMs - speedHistory.first().first > 3000) speedHistory.removeFirst()
        }
    }

    /** Experimental: a GPS-speed drop of >= 25 km/h within ~3 s. Phone accelerometers are not used. */
    private fun detectSuddenBraking(s: LocationSample, now: Long) {
        val peak = synchronized(speedHistory) { speedHistory.maxOfOrNull { it.second } } ?: return
        if (peak >= 30 && peak - s.speedKmh >= 25 && s.accuracyM <= 30 && now - lastBrakeSentMs > 15_000) {
            lastBrakeSentMs = now
            val dm = data ?: return
            nearby?.send(repo.connectedIds(), dm.hazard("SUDDEN_BRAKING", s.lat, s.lon))
            pushAlert("Sudden braking detected — nearby vehicles notified", 1)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dm = data ?: return
        val s = me
        val fresh = s != null && now - s.timestampMs <= RiskEngine.MAX_AGE_MS
        if (s != null && fresh) {
            nearby?.send(repo.connectedIds(), dm.status(s))
            detectSuddenBraking(s, now)
        }
        val nearbyList = tracker.snapshot(if (fresh) s else null, now)
        val views = nearbyList.map { n ->
            val risk = if (fresh && s != null) RiskEngine.assess(s, n.sample, now)
            else RiskAssessment(RiskLevel.NORMAL, true, "No valid own position")
            PeerView(n.sid, n.vehicleType, n.distanceM, n.direction, n.ageMs, n.sample.lat, n.sample.lon, risk, n.emergencyType,
                n.sample.bearing, n.sample.speedKmh)
        }
        views.forEach { v ->
            when (v.risk.level) {
                RiskLevel.HIGH_RISK -> {
                    pushAlertDedup("risk-${v.sid}", "Potential conflict: vehicle from your ${v.direction}", 2, 8_000)
                    speaker?.speak("risk-high", "Potential intersection conflict ahead.", 3, 8_000)
                }
                RiskLevel.CAUTION -> {
                    pushAlertDedup("risk-${v.sid}", "Vehicle approaching from your ${v.direction}", 1, 10_000)
                    speaker?.speak("risk-caution-${v.direction}", "Vehicle approaching from your ${v.direction}.", 1, 20_000)
                }
                RiskLevel.NORMAL -> Unit
            }
        }
        val warning = when {
            s == null -> "Waiting for GPS…"
            !fresh -> "GPS position is stale — risk assessment paused"
            s.accuracyM > RiskEngine.MAX_ACCURACY_M -> "GPS accuracy ${s.accuracyM.toInt()} m — insufficient for risk assessment"
            else -> null
        }
        _state.update {
            it.copy(self = s, speed = synchronized(speedFilter) { speedFilter.read(now) }, peers = views, connected = repo.count(), dataQualityWarning = warning,
                alerts = it.alerts.filter { a -> now - a.atMs < 30_000 },
                laneRequests = it.laneRequests.filterValues { r -> now - r.atMs < MERGE_TTL_MS },
                mergeAlerts = it.mergeAlerts.filter { a -> now - a.atMs < MERGE_TTL_MS },
                myMerge = it.myMerge?.takeIf { m -> now - m.atMs < MERGE_TTL_MS })
        }
    }

    private val lastAlertByKey = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun pushAlertDedup(key: String, text: String, level: Int, cooldownMs: Long) {
        val now = System.currentTimeMillis()
        if (now - (lastAlertByKey[key] ?: 0L) < cooldownMs) return
        lastAlertByKey[key] = now
        pushAlert(text, level)
    }

    private fun pushAlert(text: String, level: Int) {
        val a = AlertItem(alertIds.incrementAndGet(), text, level, System.currentTimeMillis())
        _state.update { st -> st.copy(alerts = (listOf(a) + st.alerts).sortedByDescending { it.level }.take(5)) }
    }

    private fun describe(sid: String): String {
        val n = tracker.snapshot(me, System.currentTimeMillis()).firstOrNull { it.sid == sid } ?: return "nearby"
        return "${n.distanceM.toInt()} m ${n.direction}"
    }

    private fun handleEvent(e: PeerEvent) {
        when (e) {
            is PeerEvent.Status -> tracker.update(e.sid, e.vehicleType, e.sample, e.emergencyType)
            is PeerEvent.PeerAuthenticated -> _state.update { it.copy(comm = "Authenticated peer ${e.sid.takeLast(4)}") }
            is PeerEvent.LaneChange -> onLaneChange(e)
            is PeerEvent.Emergency -> {
                if (e.verified) {
                    val name = e.etype.replace('_', ' ')
                    pushAlertDedup("em-${e.sid}", "EMERGENCY: $name ${describe(e.sid)}", 3, 10_000)
                    speaker?.speak("emergency", "Emergency vehicle detected nearby.", 3, 12_000)
                } else {
                    pushAlertDedup("em-unv-${e.sid}", "Unverified emergency claim ignored", 0, 30_000)
                }
            }
            is PeerEvent.Hazard -> {
                val cur = me ?: return
                val d = com.drivelink.location.Geo.distanceM(cur.lat, cur.lon, e.lat, e.lon)
                if (d > 2000) return
                val label = e.kind.lowercase().replace('_', ' ')
                pushAlertDedup("hz-${e.sid}-${e.kind}", "Hazard: $label, ${d.toInt()} m away", if (e.kind == "ACCIDENT" || e.kind == "SUDDEN_BRAKING") 2 else 1, 20_000)
                speaker?.speak("hz-${e.kind}", "Hazard ahead: $label.", 2, 20_000)
            }
        }
    }

    // ---- merge signalling (advisory only; never controls a vehicle) ----
    private var lastMergeSendMs = 0L

    private fun requesterHeadingConfidence(): Double {
        val h = HeadingTracker.heading.value
        return when { h.deg == null -> 0.0; h.source == com.drivelink.orientation.HeadingSource.GPS -> 1.0; else -> 0.8 }
    }

    private fun onLaneChange(e: PeerEvent.LaneChange) {
        val nowMs = System.currentTimeMillis()
        if (e.cancel) {
            // The requester withdrew: the alert disappears at once.
            _state.update { st -> st.copy(mergeAlerts = st.mergeAlerts.filter { it.sid != e.sid }, laneRequests = st.laneRequests - e.sid) }
            return
        }
        val cur = me ?: return
        val req = tracker.sample(e.sid)
        var uncertain = true
        var side = "nearby"
        if (req != null) {
            // Re-check on the receiving side with the requester's own heading: ignore requests that are not about me.
            val a = com.drivelink.driving.MergeRecipientSelector.assess(req, e.direction, cur, nowMs, if (req.speedKmh >= 5.0) 1.0 else 0.8)
            if (a.verdict == com.drivelink.driving.MergeVerdict.IGNORE) return
            uncertain = a.verdict == com.drivelink.driving.MergeVerdict.NOTIFY_UNCERTAIN
            side = com.drivelink.location.Geo.relativeDirection(cur.bearing, com.drivelink.location.Geo.bearingDeg(cur.lat, cur.lon, req.lat, req.lon))
        }
        val fresh = _state.value.mergeAlerts.none { it.sid == e.sid && it.direction == e.direction }
        _state.update { st -> st.copy(
            laneRequests = st.laneRequests + (e.sid to LaneRequest(e.direction, nowMs)),
            mergeAlerts = st.mergeAlerts.filter { it.sid != e.sid } + MergeAlert(e.sid, e.direction, uncertain, side, nowMs)) }
        // Speak once per new request (dedupe window avoids repeating while the same alert is on screen).
        if (fresh) speaker?.speak("lane-${e.sid}", if (uncertain) "A vehicle nearby may be merging." else "Vehicle on your $side merging ${e.direction.lowercase()}.", 2, 6_000)
    }

    fun sendLaneChange(dir: String) {
        val dm = data ?: return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastMergeSendMs < 3_000) return // no spam: one signal per 3 s
        val cur = me ?: return pushAlert("No GPS fix — cannot signal a merge", 0)
        val hc = requesterHeadingConfidence()
        val recipients = repo.connectedIds().filter { ep ->
            val sid = repo.get(ep)?.identity?.sid ?: return@filter false
            val s = tracker.sample(sid) ?: return@filter false
            com.drivelink.driving.MergeRecipientSelector.assess(cur, dir, s, nowMs, hc).verdict != com.drivelink.driving.MergeVerdict.IGNORE
        }
        val side = dir.lowercase()
        if (recipients.isEmpty()) {
            _state.update { it.copy(myMerge = null) }
            return pushAlert("No vehicle detected on your $side. This does not guarantee the lane is clear — check mirrors", 0)
        }
        lastMergeSendMs = nowMs
        nearby?.send(recipients, dm.laneChange(dir))
        _state.update { it.copy(myMerge = MyMerge(dir, nowMs, recipients, recipients.size)) }
        pushAlert("Merge $side signalled to ${recipients.size} vehicle${if (recipients.size > 1) "s" else ""} — advisory only", 0)
    }

    /** Withdraws my merge signal; recipients' alerts disappear immediately. */
    fun cancelLaneChange() {
        val dm = data ?: return
        val m = _state.value.myMerge ?: return
        nearby?.send(m.recipients.filter { repo.get(it) != null }, dm.laneChange(m.direction, cancel = true))
        _state.update { it.copy(myMerge = null) }
        pushAlert("Merge signal cancelled", 0)
    }

    fun sendHazard(kind: String) {
        val dm = data ?: return
        val s = me ?: return pushAlert("No GPS fix — cannot report hazard", 0)
        nearby?.send(repo.connectedIds(), dm.hazard(kind, s.lat, s.lon))
        pushAlert("Reported ${kind.lowercase().replace('_', ' ')}", 0)
    }

    fun sendEmergency() {
        val dm = data ?: return
        if (credential == null || config.vehicleType !in CommunicationProtocol.EMERGENCY_TYPES) return
        nearby?.send(repo.connectedIds(), dm.emergency(config.vehicleType))
        pushAlert("Emergency alert broadcast", 0)
    }
}
