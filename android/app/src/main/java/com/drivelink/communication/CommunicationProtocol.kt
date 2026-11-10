package com.drivelink.communication

import org.json.JSONObject

enum class MessageType { HELLO, VEHICLE_STATUS, LANE_CHANGE_REQUEST, EMERGENCY_ALERT, HAZARD_ALERT }

object CommunicationProtocol {
    const val PROTOCOL = "DRIVELINK"
    const val VERSION = 1
    const val SERVICE_ID = "com.drivelink.v1"
    const val NAME_PREFIX = "DL1-"
    const val MAX_PACKET_BYTES = 4096
    const val MAX_CLOCK_SKEW_SEC = 30L
    const val MAX_MESSAGES_PER_SECOND = 10
    const val MAX_PEERS = 6
    const val PEER_STALE_MS = 10_000L

    val HAZARD_KINDS = setOf("ACCIDENT", "OBSTRUCTION", "BROKEN_DOWN", "ROAD_DAMAGE", "ROAD_CLOSURE", "SUDDEN_BRAKING", "OTHER")
    val EMERGENCY_TYPES = setOf("ambulance", "police", "fire_service")

    /** Returns an error string if the body is invalid for the type, null when valid. */
    fun validateBody(type: MessageType, b: JSONObject): String? = when (type) {
        MessageType.HELLO -> when {
            b.optString("pub").length !in 60..200 -> "bad pub"
            b.optString("vtype").length > 20 -> "bad vtype"
            b.optString("vid").length > 24 -> "bad vid"
            else -> null
        }
        MessageType.VEHICLE_STATUS -> {
            val lat = b.optDouble("lat", Double.NaN); val lon = b.optDouble("lon", Double.NaN)
            val spd = b.optDouble("spd", Double.NaN); val hdg = b.optDouble("hdg", Double.NaN)
            val acc = b.optDouble("acc", Double.NaN)
            when {
                lat.isNaN() || lat !in -90.0..90.0 -> "bad lat"
                lon.isNaN() || lon !in -180.0..180.0 -> "bad lon"
                spd.isNaN() || spd !in 0.0..400.0 -> "bad speed"
                hdg.isNaN() || hdg !in 0.0..360.0 -> "bad heading"
                acc.isNaN() || acc !in 0.0..10_000.0 -> "bad accuracy"
                else -> null
            }
        }
        MessageType.LANE_CHANGE_REQUEST -> when {
            b.optString("dir") !in setOf("LEFT", "RIGHT") -> "bad dir"
            b.has("act") && b.optString("act") !in setOf("REQUEST", "CANCEL") -> "bad act" // "act" is optional: older peers send only dir
            else -> null
        }
        MessageType.EMERGENCY_ALERT -> if (b.optString("etype") in EMERGENCY_TYPES) null else "bad etype"
        MessageType.HAZARD_ALERT -> {
            val lat = b.optDouble("lat", Double.NaN); val lon = b.optDouble("lon", Double.NaN)
            when {
                b.optString("kind") !in HAZARD_KINDS -> "bad kind"
                lat.isNaN() || lat !in -90.0..90.0 || lon.isNaN() || lon !in -180.0..180.0 -> "bad position"
                else -> null
            }
        }
    }
}
