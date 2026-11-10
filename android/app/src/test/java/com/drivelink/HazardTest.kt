package com.drivelink

import com.drivelink.hazards.Hazard
import com.drivelink.hazards.HazardCategory
import com.drivelink.hazards.HazardConfidence
import com.drivelink.hazards.HazardGeometry
import com.drivelink.hazards.HazardParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardTest {
    private fun h(lat: Double, lon: Double, cat: HazardCategory = HazardCategory.ACCIDENT, expires: Long = 10_000_000L, active: Boolean = true, id: String = "x") =
        Hazard(id, cat, lat, lon, null, 0, 0, expires, active, 1, 0, HazardConfidence.LOW, null)

    private val json = """{"id":"a1","category":"accident","lat":28.6139,"lon":77.209,"description":"Two cars",
        "created_at":"2026-11-09T05:12:33.123456+00:00","updated_at":"2026-11-09T05:15:00+00:00","expires_at":"2026-11-09T06:12:33.123456+00:00",
        "status":"active","confirmations":2,"clear_votes":0,"confidence":"medium","my_vote":null}"""

    @Test fun parsesServerJsonIncludingMicrosecondOffsetTimestamps() {
        val x = HazardParser.parse(JSONObject(json))!!
        assertEquals(HazardCategory.ACCIDENT, x.category)
        assertEquals(2, x.confirmations)
        assertEquals(HazardConfidence.MEDIUM, x.confidence)
        assertNull(x.myVote)
        assertEquals(3_600_000L, x.expiresAtMs - x.createdAtMs)
    }

    @Test fun malformedOrUnknownEntriesAreDroppedNotFatal() {
        assertNull(HazardParser.parse(JSONObject("""{"id":"z","category":"ufo","lat":1,"lon":1}""")))
        assertNull(HazardParser.parse(JSONObject(json.replace("28.6139", "95"))))
        assertNull(HazardParser.parse(JSONObject(json.replace("2026-11-09T06:12:33.123456+00:00", "tomorrow"))))
        val list = HazardParser.parseList("[$json, {\"bad\":1}, 5]")
        assertEquals(1, list.size)
        assertTrue(HazardParser.parseList("not json").isEmpty())
    }

    @Test fun expiredIsNeverActive() {
        assertTrue(h(0.0, 0.0, expires = 100).isActive(99))
        assertFalse(h(0.0, 0.0, expires = 100).isActive(100))
        assertFalse(h(0.0, 0.0, expires = 100, active = false).isActive(1))
    }

    @Test fun everyCategoryHasADistinctKey() {
        assertEquals(8, HazardCategory.entries.map { it.key }.toSet().size)
        assertEquals(HazardCategory.POTHOLE, HazardCategory.fromKey("pothole"))
        assertNull(HazardCategory.fromKey("obstruction"))
    }

    // Straight east-west road at lat 28, ~1.0 km long.
    private val road = listOf(28.0 to 77.000, 28.0 to 77.010)

    @Test fun distanceToPolylineAndAlongTrack() {
        val (d, along) = HazardGeometry.distanceToPolyline(28.0003, 77.005, road)
        assertEquals(33.0, d, 3.0)
        assertEquals(490.0, along, 15.0)
        val (dEnd, alongEnd) = HazardGeometry.distanceToPolyline(28.0, 77.02, road)
        assertEquals(980.0, dEnd, 30.0) // clamped to the end point
        assertEquals(980.0, alongEnd, 30.0)
    }

    @Test fun routeHazardsRespectBufferActivityAndOrder() {
        val near2 = h(28.0002, 77.008, id = "b"); val near1 = h(28.0001, 77.002, id = "a")
        val far = h(28.01, 77.005, id = "far"); val expired = h(28.0, 77.005, expires = 5, id = "exp")
        val r = HazardGeometry.hazardsOnRoute(road, listOf(near2, far, expired, near1), nowMs = 10, bufferM = 50.0)
        assertEquals(listOf("a", "b"), r.map { it.hazard.id })
        assertTrue(r[0].alongRouteM < r[1].alongRouteM)
    }

    @Test fun nearbyRelevantFiltersDistanceBehindAndExpired() {
        val ahead = h(28.002, 77.0, id = "ahead")            // ~220 m north
        val behind = h(27.998, 77.0, id = "behind")          // ~220 m south
        val far = h(28.2, 77.0, id = "far")                  // ~22 km
        val old = h(28.001, 77.0, expires = 5, id = "old")
        val moving = HazardGeometry.nearbyRelevant(listOf(behind, far, ahead, old), 28.0, 77.0, 0.0, true, 10)
        assertEquals(listOf("ahead"), moving.map { it.first.id })
        val parked = HazardGeometry.nearbyRelevant(listOf(behind, far, ahead, old), 28.0, 77.0, 0.0, false, 10)
        assertEquals(listOf("ahead", "behind"), parked.map { it.first.id }.sortedBy { it })
    }

    @Test fun bboxAroundScalesLongitudeWithLatitude() {
        val eq = HazardGeometry.bboxAround(0.0, 0.0, 1000.0)
        val hi = HazardGeometry.bboxAround(60.0, 0.0, 1000.0)
        assertEquals(eq.maxLat - eq.minLat, hi.maxLat - hi.minLat, 1e-9)
        assertTrue((hi.maxLon - hi.minLon) > 1.9 * (eq.maxLon - eq.minLon))
    }

    @Test fun routeBoxesStayWithinServerLimitAndCapCount() {
        val long = (0..100).map { 20.0 + it * 0.1 to 70.0 + it * 0.1 } // ~1500 km diagonal
        val boxes = HazardGeometry.bboxesForRoute(long, 500.0, maxSpanDeg = 1.0, maxBoxes = 6)
        assertTrue(boxes.size in 1..6)
        boxes.forEach { assertTrue(it.maxLat - it.minLat <= 2.0 && it.maxLon - it.minLon <= 2.0) }
        assertTrue(HazardGeometry.bboxesForRoute(emptyList(), 100.0).isEmpty())
        val short = HazardGeometry.bboxesForRoute(road, 100.0)
        assertEquals(1, short.size)
        assertTrue(short[0].minLon < 77.0 && short[0].maxLon > 77.01)
    }

    @Test fun textHelpers() {
        assertEquals("just now", HazardGeometry.ageText(30_000, 0))
        assertEquals("5 min ago", HazardGeometry.ageText(5 * 60_000, 0))
        assertEquals("2 h ago", HazardGeometry.ageText(2 * 3_600_000L, 0))
        assertEquals("soon", HazardGeometry.remainingText(0, 30_000))
        assertEquals("in about 25 min", HazardGeometry.remainingText(0, 25 * 60_000L + 5))
        assertEquals("in about 3 h", HazardGeometry.remainingText(0, 3 * 3_600_000L))
        assertEquals("340 m", HazardGeometry.distanceText(338.0))
        assertEquals("1.5 km", HazardGeometry.distanceText(1500.0))
        assertNotNull(HazardCategory.entries.firstOrNull())
    }
}
