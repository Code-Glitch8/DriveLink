package com.drivelink.vehicles

import org.json.JSONObject

data class Vehicle(
    val id: String,
    val driveLinkId: String,
    val registrationNumber: String,
    val region: String,
    val type: String,
    val manufacturer: String,
    val model: String,
    val year: Int?,
    val colour: String,
    val nickname: String,
    val verification: String,
) {
    val displayName: String get() = nickname.ifBlank { "$manufacturer $model".trim().ifBlank { driveLinkId } }
    val isEmergencyType: Boolean get() = type in setOf("ambulance", "police", "fire_service")

    companion object {
        fun from(o: JSONObject) = Vehicle(
            id = o.getString("id"),
            driveLinkId = o.getString("drive_link_vehicle_id"),
            registrationNumber = o.optString("registration_number"),
            region = o.optString("registration_region"),
            type = o.optString("vehicle_type", "other"),
            manufacturer = o.optString("manufacturer").let { if (it == "null") "" else it },
            model = o.optString("model").let { if (it == "null") "" else it },
            year = if (o.isNull("manufacturing_year")) null else o.optInt("manufacturing_year"),
            colour = o.optString("colour").let { if (it == "null") "" else it },
            nickname = o.optString("nickname").let { if (it == "null") "" else it },
            verification = o.optString("verification_status", "unverified"),
        )
    }
}

val VEHICLE_TYPES = listOf("car", "motorcycle", "scooter", "bus", "truck", "ambulance", "police", "fire_service", "other")
