package com.drivelink.safety

import com.drivelink.location.Geo
import com.drivelink.location.LocationSample

enum class RiskLevel { NORMAL, CAUTION, HIGH_RISK }

data class RiskAssessment(
    val level: RiskLevel,
    val insufficientData: Boolean,
    val reason: String,
    val ttcSeconds: Double? = null,
    val minDistanceM: Double? = null,
)

/**
 * EXPERIMENTAL awareness logic. Not an ADAS and not certified. It extrapolates both vehicles in a straight
 * line, finds the time of closest approach and compares the miss distance with an uncertainty margin built
 * from the reported GPS accuracies. Proximity alone never triggers a warning.
 */
object RiskEngine {
    const val MAX_AGE_MS = 5_000L
    const val MAX_ACCURACY_M = 50.0

    fun assess(me: LocationSample, other: LocationSample, nowMs: Long): RiskAssessment {
        if (nowMs - me.timestampMs > MAX_AGE_MS || nowMs - other.timestampMs > MAX_AGE_MS)
            return RiskAssessment(RiskLevel.NORMAL, true, "Position data too old")
        if (me.accuracyM > MAX_ACCURACY_M || other.accuracyM > MAX_ACCURACY_M)
            return RiskAssessment(RiskLevel.NORMAL, true, "GPS accuracy too low to assess risk")

        val r = Geo.toLocal(me.lat, me.lon, other.lat, other.lon)
        val vm = Geo.velocity(me.speedKmh, me.bearing)
        val vo = Geo.velocity(other.speedKmh, other.bearing)
        val vx = vo.x - vm.x
        val vy = vo.y - vm.y
        val v2 = vx * vx + vy * vy
        if (v2 < 0.25) return RiskAssessment(RiskLevel.NORMAL, false, "No relative motion")

        val t = -(r.x * vx + r.y * vy) / v2
        if (t < 0) return RiskAssessment(RiskLevel.NORMAL, false, "Vehicles are diverging")

        val cx = r.x + vx * t
        val cy = r.y + vy * t
        val dMin = kotlin.math.sqrt(cx * cx + cy * cy)
        val margin = 8.0 + me.accuracyM + other.accuracyM

        val level = when {
            t <= 6.0 && dMin <= margin -> RiskLevel.HIGH_RISK
            t <= 12.0 && dMin <= margin * 2.5 -> RiskLevel.CAUTION
            else -> RiskLevel.NORMAL
        }
        val reason = if (level == RiskLevel.NORMAL) "Paths do not conflict soon" else "Projected paths converge"
        return RiskAssessment(level, false, reason, t, dMin)
    }
}
