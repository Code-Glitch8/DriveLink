package com.drivelink.location

import com.drivelink.communication.CommunicationProtocol
import java.util.concurrent.ConcurrentHashMap

data class TrackedVehicle(
    val sid: String,
    val vehicleType: String,
    val sample: LocationSample,
    val emergencyType: String?, // only set when verified by a server-signed credential
)

data class NearbyVehicle(
    val sid: String,
    val vehicleType: String,
    val sample: LocationSample,
    val distanceM: Double,
    val direction: String,
    val ageMs: Long,
    val emergencyType: String?,
)

/** Latest valid state per connected peer. Disconnected or silent peers are dropped, never shown as present. */
class VehicleTracker {
    private val vehicles = ConcurrentHashMap<String, TrackedVehicle>()

    fun update(sid: String, vehicleType: String, sample: LocationSample, emergencyType: String?) {
        vehicles[sid] = TrackedVehicle(sid, vehicleType, sample, emergencyType)
    }

    fun remove(sid: String) { vehicles.remove(sid) }
    fun clear() = vehicles.clear()

    fun snapshot(me: LocationSample?, nowMs: Long): List<NearbyVehicle> {
        vehicles.values.removeIf { nowMs - it.sample.timestampMs > CommunicationProtocol.PEER_STALE_MS }
        if (me == null) return emptyList()
        return vehicles.values.map {
            val d = Geo.distanceM(me.lat, me.lon, it.sample.lat, it.sample.lon)
            val brg = Geo.bearingDeg(me.lat, me.lon, it.sample.lat, it.sample.lon)
            NearbyVehicle(it.sid, it.vehicleType, it.sample, d, Geo.relativeDirection(me.bearing, brg),
                nowMs - it.sample.timestampMs, it.emergencyType)
        }.sortedBy { it.distanceM }
    }
}
