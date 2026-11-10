package com.drivelink.driving

import com.drivelink.location.Geo
import com.drivelink.location.LocationSample
import com.drivelink.orientation.HeadingMath
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

enum class MergeVerdict { NOTIFY, NOTIFY_UNCERTAIN, IGNORE }

/**
 * @param forwardM metres ahead (+) / behind (-) of the requester, in the requester's frame
 * @param lateralM metres to the requester's RIGHT (+) / LEFT (-)
 * @param confidence 0..1 that this vehicle is on the requested side and close enough to be affected
 */
data class MergeAssessment(
    val verdict: MergeVerdict,
    val confidence: Double,
    val reason: String,
    val forwardM: Double = 0.0,
    val lateralM: Double = 0.0,
)

/**
 * Decides whether another vehicle should be told about a merge request. "Left/right" is the REQUESTER's left/right
 * (relative to its heading), never screen-left. The same function runs on the sender (to choose recipients) and
 * on the receiver (to double-check a request it got), so a stale or wrong position on one side cannot spam others.
 *
 * Civilian GPS is ~3–10 m accurate, which is about the width of a lane, so the result is a CONFIDENCE, not a fact:
 *  - NOTIFY: confidently on the requested side and in the merge window.
 *  - NOTIFY_UNCERTAIN: very close and plausibly affected but the side is not certain (shown as "may be merging").
 *  - IGNORE: everything else — other side, far away, oncoming, stale or too inaccurate.
 * This is advisory information only; nothing here controls a vehicle.
 */
object MergeRecipientSelector {
    const val MAX_AGE_MS = 4_000L
    const val MAX_ACCURACY_M = 40.0
    const val NOTIFY_CONFIDENCE = 0.4
    const val UNCERTAIN_CONFIDENCE = 0.15
    const val UNCERTAIN_MAX_DISTANCE_M = 40.0
    const val NO_HEADING_MAX_DISTANCE_M = 25.0
    const val ALONGSIDE_M = 15.0
    private const val LATERAL_MAX_M = 10.0
    private const val AHEAD_WINDOW_M = 15.0
    private const val BEHIND_BASE_M = 30.0
    private const val MOVING_KMH = 5.0

    /**
     * @param requesterHeadingConfidence 1.0 = GPS course while moving, ~0.8 = compass while slow, 0.0 = unknown
     */
    fun assess(
        requester: LocationSample, direction: String, other: LocationSample, nowMs: Long,
        requesterHeadingConfidence: Double = 1.0,
    ): MergeAssessment {
        val age = max(nowMs - requester.timestampMs, nowMs - other.timestampMs)
        if (age > MAX_AGE_MS) return MergeAssessment(MergeVerdict.IGNORE, 0.0, "stale position")
        if (requester.accuracyM > MAX_ACCURACY_M || other.accuracyM > MAX_ACCURACY_M) return MergeAssessment(MergeVerdict.IGNORE, 0.0, "GPS accuracy too low")
        if (direction != "LEFT" && direction != "RIGHT") return MergeAssessment(MergeVerdict.IGNORE, 0.0, "bad direction")

        val rel = Geo.toLocal(requester.lat, requester.lon, other.lat, other.lon)
        val dist = hypot(rel.x, rel.y)

        if (requesterHeadingConfidence <= 0.0) {
            // Without a heading we cannot say which side is "left". Only warn vehicles that are practically alongside.
            return if (dist <= NO_HEADING_MAX_DISTANCE_M) MergeAssessment(MergeVerdict.NOTIFY_UNCERTAIN, 0.3, "heading unknown, vehicle very close")
            else MergeAssessment(MergeVerdict.IGNORE, 0.0, "heading unknown")
        }

        val h = Math.toRadians(requester.bearing)
        val forward = rel.x * sin(h) + rel.y * cos(h)
        val lateral = rel.x * cos(h) - rel.y * sin(h)      // + = requester's right
        val s = if (direction == "RIGHT") 1.0 else -1.0
        val toward = lateral * s                           // + = on the requested side
        val u = max(3.0, hypot(requester.accuracyM, other.accuracyM))

        val bothMoving = requester.speedKmh >= MOVING_KMH && other.speedKmh >= MOVING_KMH
        if (bothMoving && HeadingMath.absDelta(requester.bearing, other.bearing) > 60.0)
            return MergeAssessment(MergeVerdict.IGNORE, 0.0, "different direction of travel", forward, lateral)

        // A vehicle approaching from behind needs more notice: widen the rear window by ~3 s of closing speed.
        val otherAlong = if (other.speedKmh >= MOVING_KMH) other.speedKmh / 3.6 * cos(Math.toRadians(HeadingMath.delta(requester.bearing, other.bearing))) else 0.0
        val closing = max(0.0, otherAlong - requester.speedKmh / 3.6)
        val behindWindow = BEHIND_BASE_M + closing * 3.0
        if (forward > AHEAD_WINDOW_M + u || forward < -(behindWindow + u))
            return MergeAssessment(MergeVerdict.IGNORE, 0.0, "outside the merge window", forward, lateral)
        if (toward > LATERAL_MAX_M + 0.5 * u)
            return MergeAssessment(MergeVerdict.IGNORE, 0.0, "too far to the side", forward, lateral)

        // P(vehicle really is on the requested side) given position noise u (logistic ≈ normal CDF). 0.5 is a coin flip,
        // so the side confidence is rescaled to 0 (coin flip) .. 1 (certain).
        val pSide = 1.0 / (1.0 + exp(-1.7 * toward / u))
        val sideConf = max(0.0, 2.0 * pSide - 1.0)
        // Adjacent-lane band: ~1.5–6 m to the side is ideal; closer is likely the same lane, farther fades out.
        val band = when {
            toward < 1.5 -> 0.5 + max(0.0, toward) / 3.0
            toward <= 6.0 + 0.5 * u -> 1.0
            else -> ((LATERAL_MAX_M + 0.5 * u - toward) / (LATERAL_MAX_M - 6.0)).coerceIn(0.0, 1.0)
        }
        val ageFactor = if (age <= 2_000) 1.0 else 0.7
        val conf = (sideConf * band * requesterHeadingConfidence * ageFactor).coerceIn(0.0, 1.0)

        val verdict = when {
            conf >= NOTIFY_CONFIDENCE -> MergeVerdict.NOTIFY
            conf >= UNCERTAIN_CONFIDENCE && dist <= UNCERTAIN_MAX_DISTANCE_M -> MergeVerdict.NOTIFY_UNCERTAIN
            // GPS too noisy to tell sides, but the vehicle is practically alongside: say "may be merging" rather than stay silent.
            dist <= ALONGSIDE_M && toward > -0.25 * u -> MergeVerdict.NOTIFY_UNCERTAIN
            else -> MergeVerdict.IGNORE
        }
        return MergeAssessment(verdict, conf, if (verdict == MergeVerdict.IGNORE) "not confidently on the requested side" else "on the requested side", forward, lateral)
    }
}
