package com.drivelink

import com.drivelink.navigation.Place
import com.drivelink.navigation.PlaceCategory
import com.drivelink.search.PhotonGeocoder
import com.drivelink.search.SearchCache
import com.drivelink.search.SearchSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private val sample = """{"type":"FeatureCollection","features":[
      {"geometry":{"coordinates":[77.2295,28.6129],"type":"Point"},"type":"Feature","properties":{"osm_key":"tourism","osm_value":"attraction","name":"India Gate","city":"New Delhi","state":"Delhi","country":"India","street":"Rajpath","type":"house"}},
      {"geometry":{"coordinates":[77.1025,28.7041],"type":"Point"},"type":"Feature","properties":{"osm_key":"place","osm_value":"city","name":"Delhi","state":"Delhi","country":"India","type":"city"}},
      {"geometry":{"coordinates":[77.20,28.60],"type":"Point"},"type":"Feature","properties":{"osm_key":"building","street":"MG Road","housenumber":"12","city":"Gurugram","state":"Haryana","country":"India","type":"house"}},
      {"geometry":{"coordinates":[77.3,28.5],"type":"Point"},"type":"Feature","properties":{"osm_key":"amenity","osm_value":"cafe","name":"Cafe Coffee Day","district":"Saket","city":"New Delhi","type":"house"}},
      {"geometry":{"coordinates":[200,95],"type":"Point"},"type":"Feature","properties":{"name":"Bad coords"}},
      {"geometry":{"coordinates":[77.3],"type":"Point"},"type":"Feature","properties":{"name":"Short coords"}},
      {"geometry":{"coordinates":[77.3,28.5],"type":"Point"},"type":"Feature","properties":{}},
      {"type":"Feature"}]}"""

    @Test fun parsesPhotonFeaturesAndSkipsMalformedOnes() {
        val r = PhotonGeocoder.parse(sample)
        assertEquals(listOf("India Gate", "Delhi", "12 MG Road", "Cafe Coffee Day"), r.map { it.name })
        assertEquals(28.6129, r[0].lat, 1e-9); assertEquals(77.2295, r[0].lon, 1e-9) // GeoJSON order is lon,lat
        assertTrue(PhotonGeocoder.parse("garbage").isEmpty())
        assertTrue(PhotonGeocoder.parse("""{"features":null}""").isEmpty())
    }

    @Test fun categoriesAndSubtitles() {
        val r = PhotonGeocoder.parse(sample)
        assertEquals(PlaceCategory.LANDMARK, r[0].category)
        assertEquals(PlaceCategory.CITY, r[1].category)
        assertEquals(PlaceCategory.ADDRESS, r[2].category)
        assertEquals(PlaceCategory.FOOD, r[3].category)
        assertEquals("Rajpath, New Delhi, Delhi", r[0].subtitle)
        assertEquals("India", r[1].subtitle)                  // title not repeated
        assertEquals("Saket, New Delhi", r[3].subtitle)
    }

    @Test fun urlCarriesQueryLimitAndBias() {
        val u = PhotonGeocoder.buildUrl("https://photon.example/", "  india gate ", 28.6139, 77.209, 8)
        assertEquals("https://photon.example/api/?q=india%20gate&limit=8&lat=28.6139&lon=77.2090", u)
        assertEquals("https://photon.example/api/?q=x&limit=15", PhotonGeocoder.buildUrl("https://photon.example", "x", null, null, 99))
    }

    @Test fun rankingPrefersExactThenPrefixThenNearer() {
        val places = listOf(
            Place("Central Mall Annex", 28.60, 77.20, category = PlaceCategory.SHOP),
            Place("Central Mall", 28.90, 77.50, category = PlaceCategory.SHOP),   // exact but far
            Place("The Central Mall", 28.60, 77.21, category = PlaceCategory.SHOP),
        )
        val hits = SearchSupport.rank("central mall", places, 28.6, 77.2)
        assertEquals("Central Mall", hits[0].place.name)
        assertEquals("Central Mall Annex", hits[1].place.name)
    }

    @Test fun nearbyExactBeatsFarExact() {
        val places = listOf(Place("Metro Station", 19.0, 72.8), Place("Metro Station", 28.61, 77.21))
        val hits = SearchSupport.rank("metro station", places, 28.6, 77.2)
        assertEquals(28.61, hits[0].place.lat, 1e-9)
        assertTrue("same name far apart must be flagged ambiguous", hits.all { it.ambiguous })
    }

    @Test fun duplicatesAtTheSameSpotAreMergedButFarOnesKept() {
        val places = listOf(Place("City Hospital", 28.6000, 77.2000), Place("city hospital", 28.6003, 77.2002), Place("City Hospital", 28.9, 77.5))
        val hits = SearchSupport.rank("city hospital", places, null, null)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.ambiguous })
        assertNull(hits[0].distanceM)
    }

    @Test fun uniqueNamesAreNotAmbiguous() {
        val hits = SearchSupport.rank("india", listOf(Place("India Gate", 28.6, 77.2)), 28.6, 77.2)
        assertFalse(hits[0].ambiguous)
        assertNotNull(hits[0].distanceM)
    }

    @Test fun cacheHitsExpiresAndEvicts() {
        var now = 0L
        val c = SearchCache<String>(maxEntries = 2, ttlMs = 1000, clock = { now })
        c.put("a", "1"); c.put("b", "2")
        assertEquals("1", c.get("a"))
        c.put("c", "3")                       // evicts least recently used: b
        assertNull(c.get("b")); assertEquals("3", c.get("c"))
        now = 2000
        assertNull(c.get("a"))                // expired
    }

    @Test fun cacheKeyNormalisesCaseWhitespaceAndRoundsBias() {
        assertEquals(SearchCache.key("India  Gate", 28.6139, 77.209), SearchCache.key(" india gate", 28.6401, 77.2101))
        assertFalse(SearchCache.key("india gate", 28.6, 77.2) == SearchCache.key("india gate", 19.0, 72.8))
        assertTrue(SearchCache.key("x", null, null).endsWith("|-"))
    }
}
