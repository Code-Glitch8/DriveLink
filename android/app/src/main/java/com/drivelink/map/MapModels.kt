package com.drivelink.map

import com.drivelink.driving.LaneRequest
import com.drivelink.driving.PeerView
import com.drivelink.safety.RiskLevel

enum class CommStatus { LINKED, STALE }

/**
 * A vehicle to draw. Built only from real, authenticated peers reported by JourneyManager (Nearby Connections).
 * Peers are identified by a temporary session id, so [displayName] is normally null.
 */
data class MapVehicle(
    val id: String,
    val lat: Double,
    val lon: Double,
    val headingDeg: Double,
    val type: String,
    val status: CommStatus,
    val lastUpdateMs: Long,
    val displayName: String? = null,
    val approaching: Boolean = false,
    val conflict: Boolean = false,
    val mergeRequest: String? = null, // "LEFT" | "RIGHT" while a request is recent
    val emergencyType: String? = null, // only for server-credential-verified emergency vehicles
)

data class MapMe(val lat: Double, val lon: Double, val headingDeg: Double? = null)
data class MapDestination(val name: String, val lat: Double, val lon: Double)

/** Points are (lat, lon) pairs, the same shape RoutePlan uses. */
data class MapRoute(val main: List<Pair<Double, Double>>, val alternatives: List<List<Pair<Double, Double>>> = emptyList())

sealed interface CameraCommand {
    data class Center(val lat: Double, val lon: Double, val zoom: Double = 15.0) : CameraCommand
    data class Follow(val lat: Double, val lon: Double, val bearing: Double, val zoom: Double = 16.5) : CameraCommand
    data class Fit(
        val points: List<Pair<Double, Double>>,
        val leftPx: Int = 80, val topPx: Int = 80, val rightPx: Int = 80, val bottomPx: Int = 80,
    ) : CameraCommand
}

sealed interface MapStatus {
    data object Loading : MapStatus
    data object Ready : MapStatus
    /** Preferred style failed, a fallback style is showing. */
    data class Fallback(val message: String) : MapStatus
    /** No tile style could load (typically offline). Vehicles and routes are still drawn on a plain background. */
    data class Offline(val message: String) : MapStatus
}

object MapConfig {
    /** Peers older than this are not drawn. JourneyManager already drops peers silent for 10 s. */
    const val DEFAULT_VEHICLE_STALE_TIMEOUT_MS = 10_000L
    /** Markers older than this but still within the timeout are drawn dimmed and marked STALE. */
    const val VEHICLE_DIM_AFTER_MS = 5_000L
    const val MERGE_INDICATOR_MS = 8_000L
}

object MapVehicleMapper {
    fun fromPeers(
        peers: List<PeerView>,
        laneRequests: Map<String, LaneRequest>,
        nowMs: Long,
        staleTimeoutMs: Long = MapConfig.DEFAULT_VEHICLE_STALE_TIMEOUT_MS,
    ): List<MapVehicle> = peers
        .filter { it.ageMs <= staleTimeoutMs }
        .map { p ->
            val lane = laneRequests[p.sid]?.takeIf { nowMs - it.atMs <= MapConfig.MERGE_INDICATOR_MS }
            MapVehicle(
                id = p.sid, lat = p.lat, lon = p.lon, headingDeg = p.bearing, type = p.vehicleType,
                status = if (p.ageMs > MapConfig.VEHICLE_DIM_AFTER_MS) CommStatus.STALE else CommStatus.LINKED,
                lastUpdateMs = nowMs - p.ageMs,
                approaching = p.risk.level != RiskLevel.NORMAL,
                conflict = p.risk.level == RiskLevel.HIGH_RISK,
                mergeRequest = lane?.direction,
                emergencyType = p.verifiedEmergency,
            )
        }
}
