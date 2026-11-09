package com.drivelink.orientation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

/**
 * Compass heading from the device sensors, with graceful fallback:
 *   1. TYPE_ROTATION_VECTOR (fused gyro/accel/mag) — preferred
 *   2. TYPE_ACCELEROMETER + TYPE_MAGNETIC_FIELD — low-pass filtered
 *   3. nothing: [available] is false and no heading is ever emitted (the UI then relies on GPS course / North-Up)
 *
 * Heading is converted to TRUE north when [setLocation] has been called (magnetic declination).
 * For a phone held upright in a windscreen mount the "device Y" axis points up, so we remap to use the camera
 * axis (-Z) as the pointing direction; for a phone lying flat we use the top edge (+Y). The choice is made per
 * reading from the gravity vector, so unusual angles degrade smoothly instead of producing garbage.
 *
 * Lifecycle: call [start] in onStart/onResume and [stop] in onStop/onPause. Both are idempotent. Listeners are
 * registered at SENSOR_DELAY_UI (~60 ms) which is plenty for a map camera and cheap on battery.
 */
class OrientationSensor(context: Context, private val onHeading: (SensorHeading) -> Unit) {
    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotation = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private var running = false
    private var declination = 0f
    private var accuracyOk = true

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveG = false
    private var haveM = false
    private val rMat = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)
    private var lastEmitMs = 0L

    /** True if at least one usable sensor path exists on this device. */
    val available: Boolean get() = sm != null && (rotation != null || (accel != null && mag != null))
    val usingRotationVector: Boolean get() = rotation != null

    fun setLocation(lat: Double, lon: Double, altM: Double = 0.0) {
        declination = try { GeomagneticField(lat.toFloat(), lon.toFloat(), altM.toFloat(), System.currentTimeMillis()).declination } catch (_: Exception) { 0f }
    }

    fun start() {
        val m = sm ?: return
        if (running || !available) return
        running = true
        haveG = false; haveM = false
        try {
            if (rotation != null) m.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
            else {
                m.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
                m.registerListener(listener, mag, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (_: Exception) { running = false } // some OEM builds throw on restricted sensor rates
    }

    fun stop() {
        if (!running) return
        running = false
        try { sm?.unregisterListener(listener) } catch (_: Exception) {}
    }

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD)
                accuracyOk = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_LOW
        }

        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rMat, e.values)
                    emit(rMat)
                }
                Sensor.TYPE_ACCELEROMETER -> { lowPass(e.values, gravity); haveG = true; fromAccelMag() }
                Sensor.TYPE_MAGNETIC_FIELD -> { lowPass(e.values, geomagnetic); haveM = true; fromAccelMag() }
            }
        }
    }

    private fun lowPass(input: FloatArray, out: FloatArray) {
        for (i in 0..2) out[i] = if (out[i] == 0f) input[i] else out[i] + 0.15f * (input[i] - out[i])
    }

    private fun fromAccelMag() {
        if (!haveG || !haveM) return
        if (SensorManager.getRotationMatrix(rMat, null, gravity, geomagnetic)) emit(rMat)
    }

    private fun emit(r: FloatArray) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEmitMs < MIN_EMIT_INTERVAL_MS) return
        lastEmitMs = now
        // Z-axis of the device in world coordinates is (r[2], r[5], r[8]); |r[8]| ≈ 1 means the phone lies flat.
        val flat = abs(r[8]) > 0.75f
        val ok = if (flat) SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Y, remapped)
        else SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        if (!ok) return
        SensorManager.getOrientation(remapped, orient)
        val magnetic = Math.toDegrees(orient[0].toDouble())
        if (magnetic.isNaN()) return
        // Near the flat/upright boundary the pointing axis is ill-defined: report as unreliable instead of guessing.
        val ambiguous = abs(r[8]) in 0.55f..0.75f
        onHeading(SensorHeading(HeadingMath.normalize(magnetic + declination), accuracyOk && !ambiguous, System.currentTimeMillis()))
    }

    private companion object { const val MIN_EMIT_INTERVAL_MS = 50L }
}
