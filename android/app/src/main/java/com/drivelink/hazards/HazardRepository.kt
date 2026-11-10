package com.drivelink.hazards

import com.drivelink.authentication.SupabaseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where to look for hazards. [route] is the currently selected route (lat, lon), if any. */
data class HazardArea(val lat: Double, val lon: Double, val speedKmh: Double, val route: List<Pair<Double, Double>>?)

sealed interface HazardSync {
    data object Idle : HazardSync
    data class Ok(val atMs: Long) : HazardSync
    data class Failed(val message: String, val lastOkMs: Long?) : HazardSync
}

/**
 * Shared hazards from Supabase, fetched by efficient polling (Supabase Realtime is a possible later upgrade; polling
 * needs no extra connection and works through the same REST client).
 *
 * - Only the area around the user and along the selected route is requested, never "everything".
 * - Interval adapts: 12 s while driving, 30 s when slow/parked, exponential back-off to 2 min on errors, and
 *   the loop does nothing while stopped (no battery use outside the screens that need it).
 * - Hazards the server stopped returning (expired / cleared) disappear on the next poll; the UI also filters by
 *   expiry on every render so an expired hazard is never shown as active between polls.
 */
object HazardRepository {
    private val _hazards = MutableStateFlow<List<Hazard>>(emptyList())
    val hazards: StateFlow<List<Hazard>> = _hazards.asStateFlow()
    private val _sync = MutableStateFlow<HazardSync>(HazardSync.Idle)
    val sync: StateFlow<HazardSync> = _sync.asStateFlow()

    private var job: Job? = null
    @Volatile private var kick = false
    private var lastOkMs: Long? = null

    private const val NEARBY_RADIUS_M = 5_000.0
    private const val ROUTE_BUFFER_M = 300.0

    fun start(scope: CoroutineScope, token: suspend () -> String?, area: () -> HazardArea?) {
        if (job?.isActive == true) return
        job = scope.launch {
            var failures = 0
            while (isActive) {
                val a = area()
                val t = token()
                var wait = 30_000L
                if (a != null && t != null) {
                    try {
                        _hazards.value = fetch(t, a)
                        lastOkMs = System.currentTimeMillis(); failures = 0
                        _sync.value = HazardSync.Ok(lastOkMs!!)
                        wait = if (a.speedKmh > 5) 12_000L else 30_000L
                    } catch (e: Exception) {
                        failures++
                        _sync.value = HazardSync.Failed(e.message ?: "Network error", lastOkMs)
                        wait = (15_000L shl (failures - 1).coerceAtMost(3)).coerceAtMost(120_000L)
                    }
                }
                var waited = 0L
                while (waited < wait && !kick) { delay(500); waited += 500 }
                kick = false
            }
        }
    }

    fun stop() { job?.cancel(); job = null; _hazards.value = emptyList(); _sync.value = HazardSync.Idle; lastOkMs = null }

    /** Poll again soon, e.g. right after the user reported or voted. */
    fun refreshNow() { kick = true }

    internal fun boxesFor(a: HazardArea): List<BBox> {
        val boxes = ArrayList<BBox>()
        boxes += HazardGeometry.bboxAround(a.lat, a.lon, NEARBY_RADIUS_M)
        a.route?.takeIf { it.size >= 2 }?.let { boxes += HazardGeometry.bboxesForRoute(it, ROUTE_BUFFER_M) }
        return boxes
    }

    private suspend fun fetch(token: String, a: HazardArea): List<Hazard> {
        val byId = LinkedHashMap<String, Hazard>()
        for (b in boxesFor(a)) {
            val body = JSONObject().put("p_min_lat", b.minLat).put("p_min_lon", b.minLon).put("p_max_lat", b.maxLat).put("p_max_lon", b.maxLon).put("p_limit", 200)
            HazardParser.parseList(SupabaseApi.rpc("hazards_in_bbox", body, token)).forEach { byId[it.id] = it }
        }
        return byId.values.toList()
    }

    // ---- user actions ----
    suspend fun report(token: String, category: HazardCategory, lat: Double, lon: Double, accuracyM: Double?, description: String?): Hazard {
        val body = JSONObject().put("p_category", category.key).put("p_lat", lat).put("p_lon", lon)
        if (accuracyM != null) body.put("p_accuracy_m", accuracyM)
        if (!description.isNullOrBlank()) body.put("p_description", description.trim())
        val h = HazardParser.parse(JSONObject(SupabaseApi.rpc("report_hazard", body, token))) ?: throw IllegalStateException("Unexpected server reply")
        merge(h); refreshNow(); return h
    }

    /** vote = "confirm" (still there) or "clear" (it is gone). */
    suspend fun vote(token: String, id: String, vote: String, lat: Double, lon: Double): Hazard {
        val body = JSONObject().put("p_id", id).put("p_vote", vote).put("p_lat", lat).put("p_lon", lon)
        val h = HazardParser.parse(JSONObject(SupabaseApi.rpc("vote_hazard", body, token))) ?: throw IllegalStateException("Unexpected server reply")
        merge(h); refreshNow(); return h
    }

    private fun merge(h: Hazard) {
        val rest = _hazards.value.filter { it.id != h.id }
        _hazards.value = if (h.active) rest + h else rest
    }
}
