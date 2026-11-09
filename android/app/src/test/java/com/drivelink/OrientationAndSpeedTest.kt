package com.drivelink

import com.drivelink.location.SpeedFilter
import com.drivelink.location.SpeedFix
import com.drivelink.location.SpeedReading
import com.drivelink.orientation.CameraUpdateGate
import com.drivelink.orientation.GpsCourse
import com.drivelink.orientation.HeadingFusion
import com.drivelink.orientation.HeadingMath
import com.drivelink.orientation.HeadingSource
import com.drivelink.orientation.SensorHeading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadingMathTest {
    @Test fun normalizeWraps() {
        assertEquals(0.0, HeadingMath.normalize(360.0), 1e-9)
        assertEquals(350.0, HeadingMath.normalize(-10.0), 1e-9)
        assertEquals(10.0, HeadingMath.normalize(730.0), 1e-9)
    }

    @Test fun deltaTakesShortestArcAcrossNorth() {
        assertEquals(2.0, HeadingMath.delta(359.0, 1.0), 1e-9)
        assertEquals(-2.0, HeadingMath.delta(1.0, 359.0), 1e-9)
        assertEquals(180.0, HeadingMath.delta(0.0, 180.0), 1e-9)
        assertEquals(90.0, HeadingMath.delta(270.0, 0.0), 1e-9)
    }

    @Test fun lerpDoesNotSpinTheLongWay() {
        val r = HeadingMath.lerp(350.0, 10.0, 0.5)
        assertEquals(0.0, HeadingMath.absDelta(r, 0.0), 1e-9)
    }

    @Test fun meanAcrossWrap() {
        val m = HeadingMath.mean(listOf(350.0, 10.0))!!
        assertEquals(0.0, HeadingMath.absDelta(m, 0.0), 1e-6)
        assertNull(HeadingMath.mean(emptyList()))
        assertNull(HeadingMath.mean(listOf(0.0, 180.0)))
    }
}

class HeadingFusionTest {
    private fun gps(deg: Double, mps: Double, acc: Double = 5.0, hasCourse: Boolean = true, at: Long) = GpsCourse(deg, hasCourse, mps, acc, at)

    @Test fun stationaryUsesSensor() {
        val f = HeadingFusion()
        f.onSensor(SensorHeading(90.0, true, 1000)); f.onGps(gps(10.0, 0.0, at = 1000))
        val r = f.select(1100)!!
        assertEquals(HeadingSource.SENSOR, r.second); assertEquals(90.0, r.first, 1e-9)
    }

    @Test fun movingUsesGpsCourse() {
        val f = HeadingFusion()
        f.onSensor(SensorHeading(90.0, true, 1000)); f.onGps(gps(200.0, 10.0, at = 1000))
        val r = f.select(1100)!!
        assertEquals(HeadingSource.GPS, r.second); assertEquals(200.0, r.first, 1e-9)
    }

    @Test fun hysteresisKeepsPreviousSourceBetweenThresholds() {
        val f = HeadingFusion()
        f.onSensor(SensorHeading(90.0, true, 1000)); f.onGps(gps(200.0, 10.0, at = 1000))
        assertEquals(HeadingSource.GPS, f.select(1000)!!.second)
        f.onGps(gps(200.0, 2.0, at = 1100))               // between SLOW and FAST: stays on GPS
        assertEquals(HeadingSource.GPS, f.select(1100)!!.second)
        f.onGps(gps(200.0, 0.5, at = 1200))               // slow: back to sensor
        assertEquals(HeadingSource.SENSOR, f.select(1200)!!.second)
        f.onGps(gps(200.0, 2.0, at = 1300))               // between thresholds: stays on sensor
        assertEquals(HeadingSource.SENSOR, f.select(1300)!!.second)
    }

    @Test fun poorGpsAccuracyOrNoCourseFallsBackToSensor() {
        val f = HeadingFusion()
        f.onSensor(SensorHeading(45.0, true, 1000))
        f.onGps(gps(200.0, 10.0, acc = 80.0, at = 1000))
        assertEquals(HeadingSource.SENSOR, f.select(1000)!!.second)
        f.onGps(gps(200.0, 10.0, hasCourse = false, at = 1100))
        assertEquals(HeadingSource.SENSOR, f.select(1100)!!.second)
    }

    @Test fun unreliableSensorIsIgnoredAndMovingFallsBackToGps() {
        val f = HeadingFusion()
        f.onSensor(SensorHeading(45.0, false, 1000)); f.onGps(gps(120.0, 1.8, at = 1000))
        val r = f.select(1000)!!
        assertEquals(HeadingSource.GPS, r.second)
    }

    @Test fun nothingReliableMeansNoHeading() {
        val f = HeadingFusion()
        assertNull(f.select(0))
        f.onSensor(SensorHeading(45.0, false, 1000))
        f.onGps(gps(120.0, 0.2, at = 1000))
        assertNull(f.select(1000)) // stationary GPS course is never trusted
    }

    @Test fun staleInputsAreDropped() {
        val f = HeadingFusion(staleMs = 3000)
        f.onSensor(SensorHeading(45.0, true, 1000))
        assertNotNull(f.select(3500))
        assertNull(f.select(4500))
    }

    @Test fun smoothingCrossesNorthTheShortWayAndIgnoresJitter() {
        val f = HeadingFusion()
        var t = 1000L
        f.onSensor(SensorHeading(359.0, true, t)); f.onGps(gps(0.0, 0.0, at = t))
        assertEquals(359.0, f.current(t).deg!!, 1e-9)
        // jitter below the dead-band does not move the output
        t += 100; f.onSensor(SensorHeading(0.0, true, t))
        assertEquals(359.0, f.current(t).deg!!, 1e-9)
        // a real turn to 20° must pass through 0°, never through 180°
        var prev = 359.0
        repeat(40) {
            t += 100; f.onSensor(SensorHeading(20.0, true, t))
            val d = f.current(t).deg!!
            assertTrue("went the long way: $d", HeadingMath.absDelta(prev, d) < 30.0)
            prev = d
        }
        assertEquals(20.0, prev, 3.0)
    }

    @Test fun sourceSwitchSnapsInsteadOfSwinging() {
        val f = HeadingFusion()
        f.onSensor(SensorHeading(90.0, true, 1000)); f.onGps(gps(0.0, 0.0, at = 1000))
        f.current(1000)
        f.onGps(gps(270.0, 12.0, at = 1100))
        assertEquals(270.0, f.current(1100).deg!!, 1e-9)
    }
}

class CameraUpdateGateTest {
    @Test fun firstUpdateAlwaysPasses_thenJitterIsBlocked() {
        val g = CameraUpdateGate(minIntervalMs = 200, minHeadingDeg = 2.0, minMoveM = 1.5)
        assertTrue(g.shouldUpdate(0, 28.0, 77.0, 10.0))
        assertFalse(g.shouldUpdate(100, 28.0, 77.0, 30.0))       // too soon
        assertFalse(g.shouldUpdate(500, 28.0, 77.0, 10.5))       // tiny turn, no movement
        assertTrue(g.shouldUpdate(900, 28.0, 77.0, 14.0))        // real turn
        assertTrue(g.shouldUpdate(1500, 28.0001, 77.0, 14.0))    // ~11 m move
    }

    @Test fun headingWrapIsNotATurn() {
        val g = CameraUpdateGate(minIntervalMs = 0)
        assertTrue(g.shouldUpdate(0, 28.0, 77.0, 359.5))
        assertFalse(g.shouldUpdate(10, 28.0, 77.0, 0.5))
    }

    @Test fun northUpIgnoresHeadingAndOnlyFollowsPosition() {
        val g = CameraUpdateGate(minIntervalMs = 0)
        assertTrue(g.shouldUpdate(0, 28.0, 77.0, null))
        assertFalse(g.shouldUpdate(10, 28.0, 77.0, null))
        assertTrue(g.shouldUpdate(20, 28.001, 77.0, null))
    }
}

class SpeedFilterTest {
    private fun fix(mps: Double, t: Long, acc: Double = 5.0, sacc: Double = -1.0, age: Long = 100, has: Boolean = true) =
        SpeedFix(has, mps, acc, sacc, t, age)

    @Test fun noFixMeansUnavailable() {
        assertEquals(SpeedReading.Unavailable, SpeedFilter().read(0))
    }

    @Test fun firstGoodFixIsShownAndConvertedToKmh() {
        val f = SpeedFilter()
        assertTrue(f.accept(fix(10.0, 1000), 1000))
        assertEquals(SpeedReading.Value(36, false), f.read(1000))
    }

    @Test fun emaSmoothsNoise() {
        val f = SpeedFilter(alpha = 0.35)
        f.accept(fix(10.0, 1000), 1000)
        f.accept(fix(11.0, 2000), 2000)  // +1 m/s → EMA 10.35 → 37 km/h (raw would be 40)
        assertEquals(37, (f.read(2000) as SpeedReading.Value).kmh)
    }

    @Test fun hardAccelerationCatchesUpQuickly() {
        val f = SpeedFilter()
        f.accept(fix(5.0, 1000), 1000)
        f.accept(fix(12.0, 2000), 2000)
        val kmh = (f.read(2000) as SpeedReading.Value).kmh
        assertTrue("lagging: $kmh", kmh >= 35) // 5 + 7*0.7 = 9.9 m/s ≈ 36 km/h; plain EMA would give ≈ 27
    }

    @Test fun rejectsBadFixes() {
        val f = SpeedFilter()
        assertFalse(f.accept(fix(10.0, 1000, has = false), 1000))
        assertFalse(f.accept(fix(10.0, 1000, acc = 120.0), 1000))
        assertFalse(f.accept(fix(10.0, 1000, sacc = 8.0), 1000))
        assertFalse(f.accept(fix(10.0, 1000, age = 10_000), 1000))
        assertFalse(f.accept(fix(Double.NaN, 1000), 1000))
        assertFalse(f.accept(fix(-1.0, 1000), 1000))
        assertEquals(SpeedReading.Unavailable, f.read(1000))
    }

    @Test fun rejectsDuplicateAndOutOfOrderTimestamps() {
        val f = SpeedFilter()
        assertTrue(f.accept(fix(10.0, 2000), 2000))
        assertFalse(f.accept(fix(30.0, 2000), 2100))
        assertFalse(f.accept(fix(30.0, 1500), 2200))
        assertEquals(36, (f.read(2200) as SpeedReading.Value).kmh)
    }

    @Test fun stationaryNoiseSnapsToZero() {
        val f = SpeedFilter()
        f.accept(fix(0.3, 1000), 1000)
        assertEquals(SpeedReading.Value(0, true), f.read(1000))
        f.accept(fix(0.5, 2000), 2000)
        assertEquals(SpeedReading.Value(0, true), f.read(2000))
    }

    @Test fun pullingAwayFromStopStartsCleanWithoutOldAverage() {
        val f = SpeedFilter()
        f.accept(fix(20.0, 1000), 1000)
        f.accept(fix(0.1, 2000), 2000)   // stopped
        f.accept(fix(3.0, 3000), 3000)   // pulling away
        assertEquals(11, (f.read(3000) as SpeedReading.Value).kmh)
    }

    @Test fun staleFixBecomesUnavailableNotLastValue() {
        val f = SpeedFilter(staleMs = 4000)
        f.accept(fix(15.0, 1000), 1000)
        assertTrue(f.read(4000) is SpeedReading.Value)
        assertEquals(SpeedReading.Unavailable, f.read(6000))
    }

    @Test fun recoversAfterGap() {
        val f = SpeedFilter(staleMs = 4000)
        f.accept(fix(15.0, 1000), 1000)
        assertEquals(SpeedReading.Unavailable, f.read(9000))
        f.accept(fix(8.0, 9500), 9500)
        assertTrue(f.read(9500) is SpeedReading.Value)
    }

    @Test fun resetClearsState() {
        val f = SpeedFilter()
        f.accept(fix(15.0, 1000), 1000); f.reset()
        assertEquals(SpeedReading.Unavailable, f.read(1000))
        assertTrue(f.accept(fix(5.0, 500), 1100)) // old timestamp accepted again after reset
    }
}
