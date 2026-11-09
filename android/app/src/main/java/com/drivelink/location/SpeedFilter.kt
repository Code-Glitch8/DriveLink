package com.drivelink.location

/** What the speed widget should show. [kmh] is null whenever the speed is not trustworthy. */
sealed interface SpeedReading {
    /** A smoothed, trusted speed. [stationary] is true when snapped to 0 because of GPS noise. */
    data class Value(val kmh: Int, val stationary: Boolean) : SpeedReading
    /** No usable fix yet, or the last good fix is too old. The UI must show "—", never the last number. */
    data object Unavailable : SpeedReading
}

/** One raw GPS fix as seen by the speed filter. [ageMs] is how old the fix already was when we received it. */
data class SpeedFix(
    val hasSpeed: Boolean,
    val speedMps: Double,
    val accuracyM: Double,
    val speedAccuracyMps: Double,   // < 0 when the device does not report it
    val fixTimeMs: Long,            // monotonic-ish time of the fix (must increase between fixes)
    val ageMs: Long,
)

/**
 * Smooths GPS speed for display.
 *
 * - Rejects fixes without speed, with poor horizontal accuracy, with poor reported speed accuracy, that are too
 *   old, or whose timestamp does not advance (duplicates / out-of-order).
 * - Exponential moving average; a faster alpha when accelerating hard so the number does not lag badly.
 * - Stationary snap: below [STOP_MPS] (plus a margin from reported speed accuracy) the value snaps to 0 so GPS
 *   noise does not show "3 km/h" at a red light. The EMA is reset so pulling away starts clean.
 * - Staleness: if no accepted fix for [STALE_MS], [read] returns [SpeedReading.Unavailable].
 *
 * Not thread-safe; call from one thread.
 */
class SpeedFilter(
    private val alpha: Double = 0.35,
    private val maxAccuracyM: Double = 50.0,
    private val maxFixAgeMs: Long = 3_000L,
    private val staleMs: Long = STALE_MS,
) {
    private var ema: Double? = null
    private var lastAcceptedFixMs = Long.MIN_VALUE
    private var lastAcceptedNowMs = Long.MIN_VALUE
    private var stationary = false

    fun reset() { ema = null; lastAcceptedFixMs = Long.MIN_VALUE; lastAcceptedNowMs = Long.MIN_VALUE; stationary = false }

    /** Returns true if the fix was accepted. */
    fun accept(f: SpeedFix, nowMs: Long): Boolean {
        if (!f.hasSpeed || f.speedMps.isNaN() || f.speedMps < 0) return false
        if (f.accuracyM > maxAccuracyM) return false
        if (f.speedAccuracyMps >= 0 && f.speedAccuracyMps > MAX_SPEED_ACCURACY_MPS) return false
        if (f.ageMs > maxFixAgeMs) return false
        if (lastAcceptedFixMs != Long.MIN_VALUE && f.fixTimeMs <= lastAcceptedFixMs) return false

        val noise = if (f.speedAccuracyMps >= 0) f.speedAccuracyMps else 0.0
        val stopThreshold = STOP_MPS + noise.coerceAtMost(0.5)
        lastAcceptedFixMs = f.fixTimeMs
        lastAcceptedNowMs = nowMs
        if (f.speedMps < stopThreshold) {
            stationary = true
            ema = 0.0
        } else {
            val prev = ema
            val a = if (prev == null || stationary) 1.0 else if (f.speedMps - prev > 3.0) 0.7 else alpha
            ema = if (prev == null) f.speedMps else prev + (f.speedMps - prev) * a
            stationary = false
        }
        return true
    }

    fun read(nowMs: Long): SpeedReading {
        val v = ema ?: return SpeedReading.Unavailable
        if (lastAcceptedNowMs == Long.MIN_VALUE || nowMs - lastAcceptedNowMs > staleMs) return SpeedReading.Unavailable
        return SpeedReading.Value(Math.round(v * 3.6).toInt().coerceAtLeast(0), stationary)
    }

    companion object {
        const val STALE_MS = 6_000L
        const val STOP_MPS = 0.6            // ≈ 2.2 km/h
        const val MAX_SPEED_ACCURACY_MPS = 3.0
    }
}
