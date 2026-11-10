package com.drivelink.hazards

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/**
 * Hazard categories. [key] is the database value (see hazard_category_config). Expiry is decided by the server;
 * [defaultTtlMinutes] is only used to show an estimate for a hazard we have just created and to cross-check
 * the client-side active filter. [relevanceRadiusM] is how far away a user can still care about it.
 */
enum class HazardCategory(val key: String, val label: String, val severity: Int, val relevanceRadiusM: Int, val defaultTtlMinutes: Int) {
    ACCIDENT("accident", "Accident", 3, 3000, 60),
    ROAD_BLOCKAGE("road_blockage", "Road blockage", 3, 3000, 90),
    CONSTRUCTION("construction", "Construction", 1, 3000, 480),
    POTHOLE("pothole", "Pothole", 1, 1500, 720),
    VEHICLE_BREAKDOWN("vehicle_breakdown", "Vehicle breakdown", 2, 2000, 45),
    TRAFFIC_CONGESTION("traffic_congestion", "Traffic congestion", 1, 5000, 30),
    ROAD_CLOSURE("road_closure", "Road closure", 3, 5000, 240),
    OTHER_DANGER("other_danger", "Other danger", 2, 2000, 60);

    companion object {
        fun fromKey(k: String?): HazardCategory? = entries.firstOrNull { it.key == k }
    }
}

enum class HazardConfidence { LOW, MEDIUM, HIGH, VERIFIED, DISPUTED;
    companion object { fun parse(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: LOW }
}

data class Hazard(
    val id: String,
    val category: HazardCategory,
    val lat: Double,
    val lon: Double,
    val description: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val expiresAtMs: Long,
    val active: Boolean,
    val confirmations: Int,
    val clearVotes: Int,
    val confidence: HazardConfidence,
    val myVote: String?,
) {
    /** Expired hazards are never active, whatever the server last said. */
    fun isActive(nowMs: Long) = active && expiresAtMs > nowMs
}

object HazardParser {
    /** Returns null for malformed / unknown-category entries instead of throwing, so one bad row cannot hide the rest. */
    fun parse(o: JSONObject): Hazard? {
        return try {
        val cat = HazardCategory.fromKey(o.optString("category")) ?: return null
        val lat = o.getDouble("lat"); val lon = o.getDouble("lon")
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        fun ts(k: String) = OffsetDateTime.parse(o.getString(k)).toInstant().toEpochMilli()
        Hazard(
            id = o.getString("id"), category = cat, lat = lat, lon = lon,
            description = if (o.isNull("description")) null else o.optString("description").takeIf { it.isNotBlank() },
            createdAtMs = ts("created_at"), updatedAtMs = ts("updated_at"), expiresAtMs = ts("expires_at"),
            active = o.optString("status") == "active",
            confirmations = o.optInt("confirmations", 1), clearVotes = o.optInt("clear_votes", 0),
            confidence = HazardConfidence.parse(o.optString("confidence")),
            myVote = if (o.isNull("my_vote")) null else o.optString("my_vote").takeIf { it.isNotBlank() },
        )
        } catch (_: Exception) { null }
    }

    fun parseList(json: String): List<Hazard> = try {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parse) }
    } catch (_: Exception) { emptyList() }
}
