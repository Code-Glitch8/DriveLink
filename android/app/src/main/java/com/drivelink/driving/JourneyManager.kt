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
)

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
        lp.start { s -> me = s; noteSpeed(s) }
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
            PeerView(n.sid, n.vehicleType, n.distanceM, n.direction, n.ageMs, n.sample.lat, n.sample.lon, risk, n.emergencyType)
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
            it.copy(self = s, peers = views, connected = repo.count(), dataQualityWarning = warning,
                alerts = it.alerts.filter { a -> now - a.atMs < 30_000 })
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
            is PeerEvent.LaneChange -> {
                val side = if (e.direction == "LEFT") "left" else "right"
                pushAlertDedup("lane-${e.sid}", "Vehicle (${describe(e.sid)}) requests to merge $side — not permission", 1, 6_000)
                speaker?.speak("lane-$side", "Vehicle requests to merge $side.", 2, 6_000)
            }
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

    // ---- driver actions ----
    fun sendLaneChange(dir: String) {
        val dm = data ?: return
        nearby?.send(repo.connectedIds(), dm.laneChange(dir))
        pushAlert("Merge ${dir.lowercase()} request sent", 0)
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
