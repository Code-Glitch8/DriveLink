package com.drivelink

import com.drivelink.driving.MergeRecipientSelector
import com.drivelink.driving.MergeVerdict
import com.drivelink.location.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeRecipientTest {
    private val lat0 = 28.0; private val lon0 = 77.0
    private val mLat = 1.0 / 111_000.0
    private val mLon = 1.0 / (111_000.0 * Math.cos(Math.toRadians(28.0)))
    private val now = 100_000L

    private fun at(eastM: Double, northM: Double, spd: Double = 50.0, hdg: Double = 0.0, acc: Double = 4.0, ts: Long = now) =
        LocationSample(lat0 + northM * mLat, lon0 + eastM * mLon, spd, hdg, acc, ts)

    private val me = at(0.0, 0.0)  // requester heading north at 50 km/h

    private fun verdict(other: LocationSample, dir: String, req: LocationSample = me, hc: Double = 1.0) =
        MergeRecipientSelector.assess(req, dir, other, now, hc).verdict

    @Test fun carOnRightBehindIsNotifiedForRightMergeButNotLeft() {
        val o = at(3.5, -12.0)
        assertEquals(MergeVerdict.NOTIFY, verdict(o, "RIGHT"))
        assertEquals(MergeVerdict.IGNORE, verdict(o, "LEFT"))
    }

    @Test fun carOnLeftIsMirrored() {
        val o = at(-3.5, -5.0)
        assertEquals(MergeVerdict.NOTIFY, verdict(o, "LEFT"))
        assertEquals(MergeVerdict.IGNORE, verdict(o, "RIGHT"))
    }

    @Test fun leftRightFollowTheRequesterHeadingNotTheScreen() {
        // Requester heading SOUTH: its right is WEST (-east). A car 3.5 m west is on its right.
        val south = at(0.0, 0.0, hdg = 180.0)
        val west = at(-3.5, 0.0, hdg = 180.0)
        assertEquals(MergeVerdict.NOTIFY, verdict(west, "RIGHT", south))
        assertEquals(MergeVerdict.IGNORE, verdict(west, "LEFT", south))
        // Heading EAST (90°): right is SOUTH.
        val east = at(0.0, 0.0, hdg = 90.0)
        val south3 = at(0.0, -3.5, hdg = 90.0)
        assertEquals(MergeVerdict.NOTIFY, verdict(south3, "RIGHT", east))
    }

    @Test fun headingAcrossNorthWrapStillWorks() {
        val req = at(0.0, 0.0, hdg = 359.0)
        assertEquals(MergeVerdict.NOTIFY, verdict(at(3.5, -4.0, hdg = 1.0), "RIGHT", req))
    }

    @Test fun farOrFarAheadVehiclesAreIgnored() {
        assertEquals(MergeVerdict.IGNORE, verdict(at(3.5, -250.0), "RIGHT"))
        assertEquals(MergeVerdict.IGNORE, verdict(at(3.5, 120.0), "RIGHT"))
        assertEquals(MergeVerdict.IGNORE, verdict(at(60.0, 0.0), "RIGHT"))   // 60 m to the side: another road
    }

    @Test fun oncomingTrafficIsIgnored() {
        assertEquals(MergeVerdict.IGNORE, verdict(at(3.5, 20.0, hdg = 180.0), "RIGHT"))
    }

    @Test fun staleOrInaccuratePositionsAreIgnored() {
        assertEquals(MergeVerdict.IGNORE, verdict(at(3.5, -5.0, ts = now - 6_000), "RIGHT"))
        assertEquals(MergeVerdict.IGNORE, verdict(at(3.5, -5.0, acc = 80.0), "RIGHT"))
        assertEquals(MergeVerdict.IGNORE, verdict(at(3.5, -5.0), "RIGHT", at(0.0, 0.0, acc = 80.0)))
    }

    @Test fun sameLaneVehicleBehindIsNotTreatedAsOnTheSide() {
        val o = at(0.0, -10.0)
        assertTrue(verdict(o, "RIGHT") != MergeVerdict.NOTIFY)
        assertTrue(verdict(o, "LEFT") != MergeVerdict.NOTIFY)
    }

    @Test fun fastApproachFromBehindWidensTheWindow() {
        val slowMe = at(0.0, 0.0, spd = 30.0)
        val farBehindSlow = at(3.5, -60.0, spd = 30.0)
        val farBehindFast = at(3.5, -60.0, spd = 110.0)
        assertEquals(MergeVerdict.IGNORE, verdict(farBehindSlow, "RIGHT", slowMe))
        assertEquals(MergeVerdict.NOTIFY, verdict(farBehindFast, "RIGHT", slowMe))
    }

    @Test fun poorAccuracyLowersConfidenceToUncertainNotCertain() {
        val noisyMe = at(0.0, 0.0, acc = 25.0)
        val o = at(3.5, -5.0, acc = 25.0)
        val a = MergeRecipientSelector.assess(noisyMe, "RIGHT", o, now)
        assertTrue("confidence should drop with accuracy: ${a.confidence}", a.confidence < 0.5)
        assertEquals(MergeVerdict.NOTIFY_UNCERTAIN, a.verdict)
    }

    @Test fun compassHeadingIsLessCertainThanGpsCourse() {
        val o = at(3.0, -6.0)
        val gps = MergeRecipientSelector.assess(me, "RIGHT", o, now, 1.0).confidence
        val compass = MergeRecipientSelector.assess(me, "RIGHT", o, now, 0.8).confidence
        assertTrue(compass < gps)
    }

    @Test fun unknownHeadingOnlyWarnsVehiclesAlongside() {
        assertEquals(MergeVerdict.NOTIFY_UNCERTAIN, verdict(at(3.5, -5.0), "RIGHT", hc = 0.0))
        assertEquals(MergeVerdict.IGNORE, verdict(at(3.5, -80.0), "RIGHT", hc = 0.0))
    }

    @Test fun badDirectionIsIgnored() {
        assertEquals(MergeVerdict.IGNORE, verdict(at(3.5, -5.0), "UP"))
    }

    @Test fun selectionPicksOnlyRelevantVehiclesFromACrowd() {
        val crowd = mapOf(
            "right-behind" to at(3.5, -10.0), "left-alongside" to at(-3.5, 0.0), "far-ahead" to at(3.5, 200.0),
            "oncoming" to at(3.5, 10.0, hdg = 180.0), "two-lanes-right" to at(7.0, -5.0), "stale" to at(3.5, -5.0, ts = now - 9_000),
        )
        val right = crowd.filter { verdict(it.value, "RIGHT") != MergeVerdict.IGNORE }.keys
        assertEquals(setOf("right-behind", "two-lanes-right"), right)
        val left = crowd.filter { verdict(it.value, "LEFT") != MergeVerdict.IGNORE }.keys
        assertEquals(setOf("left-alongside"), left)
    }
}
