package com.drivelink.search

import com.drivelink.location.Geo
import com.drivelink.navigation.Place
import com.drivelink.navigation.PlaceCategory
import java.util.Locale

/** A result ready for display: the place, its distance from the user (if known) and whether its name is ambiguous. */
data class SearchHit(val place: Place, val distanceM: Double?, val ambiguous: Boolean)

object SearchSupport {
    fun normalize(q: String): String = q.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /**
     * Removes duplicates (same normalised title within [sameSpotM] metres — Photon often returns a building and
     * its node) and ranks: text match quality first, then place importance, then distance to the user.
     * Results sharing a title but far apart are kept and flagged ambiguous so the UI shows the locality.
     */
    fun rank(query: String, places: List<Place>, myLat: Double?, myLon: Double?, sameSpotM: Double = 150.0, limit: Int = 8): List<SearchHit> {
        val q = normalize(query)
        val kept = ArrayList<Place>()
        for (p in places) {
            val dup = kept.any { k -> normalize(k.name) == normalize(p.name) && Geo.distanceM(k.lat, k.lon, p.lat, p.lon) <= sameSpotM }
            if (!dup) kept += p
        }
        val titles = kept.groupingBy { normalize(it.name) }.eachCount()
        fun dist(p: Place) = if (myLat != null && myLon != null) Geo.distanceM(myLat, myLon, p.lat, p.lon) else null
        fun score(p: Place): Double {
            val n = normalize(p.name)
            var s = when {
                n == q -> 100.0
                n.startsWith(q) -> 80.0
                n.split(' ', '-', ',').any { it.startsWith(q) } -> 60.0
                n.contains(q) -> 40.0
                else -> 20.0 // matched on address/locality rather than title
            }
            s += when (p.category) { PlaceCategory.LANDMARK -> 8.0; PlaceCategory.CITY -> 6.0; PlaceCategory.TRANSPORT, PlaceCategory.HEALTH -> 5.0; PlaceCategory.ADDRESS -> 0.0; else -> 3.0 }
            dist(p)?.let { d -> s += 10.0 / (1.0 + d / 5_000.0) } // up to +10 for "right here", fading over tens of km
            return s
        }
        return kept.sortedByDescending { score(it) }.take(limit).map { SearchHit(it, dist(it), (titles[normalize(it.name)] ?: 1) > 1) }
    }
}

/** Small TTL + size bounded cache. The clock is injectable so expiry is unit-testable. */
class SearchCache<V>(private val maxEntries: Int = 40, private val ttlMs: Long = 5 * 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private class CacheEntry<V>(val value: V, val atMs: Long)
    private val map = object : LinkedHashMap<String, CacheEntry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry<V>>?) = size > maxEntries
    }

    @Synchronized fun get(key: String): V? {
        val e = map[key] ?: return null
        if (clock() - e.atMs > ttlMs) { map.remove(key); return null }
        return e.value
    }
    @Synchronized fun put(key: String, v: V) { map[key] = CacheEntry(v, clock()) }
    @Synchronized fun clear() = map.clear()

    companion object {
        /** Bias is rounded to ~11 km cells so tiny GPS movements still hit the cache. */
        fun key(query: String, biasLat: Double?, biasLon: Double?): String =
            SearchSupport.normalize(query) + "|" + (if (biasLat != null && biasLon != null) "%.1f,%.1f".format(Locale.US, biasLat, biasLon) else "-")
    }
}
