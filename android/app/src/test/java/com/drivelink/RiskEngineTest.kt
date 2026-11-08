package com.drivelink

import com.drivelink.location.LocationSample
import com.drivelink.safety.RiskEngine
import com.drivelink.safety.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {
    private val lat0 = 28.6139
    private val lon0 = 77.2090
    private val now = 1_700_000_000_000L

    private fun at(x: Double, y: Double, kmh: Double, hdg: Double, acc: Double = 5.0, ts: Long = now): LocationSample {
        val lat = lat0 + Math.toDegrees(y / 6_371_000.0)
        val lon = lon0 + Math.toDegrees(x / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))
        return LocationSample(lat, lon, kmh, hdg, acc, ts)
    }

    @Test fun crossingPathsAreHighRisk() {
        val r = RiskEngine.assess(at(0.0, -60.0, 40.0, 0.0), at(-60.0, 0.0, 40.0, 90.0), now)
        assertEquals(RiskLevel.HIGH_RISK, r.level)
    }

    @Test fun closeButParallelOppositeIsNotHighRisk() {
        val r = RiskEngine.assess(at(-30.0, -150.0, 40.0, 0.0), at(30.0, 0.0, 40.0, 180.0), now)
        assertEquals(RiskLevel.NORMAL, r.level)
    }

    @Test fun stationaryNeighboursAreNormal() {
        val r = RiskEngine.assess(at(0.0, 0.0, 0.0, 0.0), at(5.0, 0.0, 0.0, 0.0), now)
        assertEquals(RiskLevel.NORMAL, r.level)
    }

    @Test fun divergingVehiclesAreNormal() {
        val r = RiskEngine.assess(at(0.0, 0.0, 40.0, 0.0), at(0.0, -20.0, 40.0, 180.0), now)
        assertEquals(RiskLevel.NORMAL, r.level)
    }

    @Test fun poorAccuracyGivesNoWarningAndFlagsInsufficientData() {
        val r = RiskEngine.assess(at(0.0, -60.0, 40.0, 0.0, acc = 80.0), at(-60.0, 0.0, 40.0, 90.0), now)
        assertEquals(RiskLevel.NORMAL, r.level)
        assertTrue(r.insufficientData)
    }

    @Test fun staleDataGivesNoWarning() {
        val r = RiskEngine.assess(at(0.0, -60.0, 40.0, 0.0), at(-60.0, 0.0, 40.0, 90.0, ts = now - 20_000), now)
        assertEquals(RiskLevel.NORMAL, r.level)
        assertTrue(r.insufficientData)
    }
}
