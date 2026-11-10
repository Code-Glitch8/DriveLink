package com.drivelink

import com.drivelink.communication.CommunicationProtocol
import com.drivelink.communication.Envelope
import com.drivelink.communication.MessageSerializer
import com.drivelink.communication.MessageType
import com.drivelink.location.Geo
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun envelopeRoundTrips() {
        val e = Envelope(MessageType.VEHICLE_STATUS, "DL1-abcd1234", 7, 1_700_000_000, "{\"lat\":1}", byteArrayOf(1, 2, 3))
        val d = MessageSerializer.decode(MessageSerializer.encode(e))
        assertNotNull(d)
        assertEquals(MessageType.VEHICLE_STATUS, d!!.type)
        assertEquals(7L, d.seq)
        assertEquals("{\"lat\":1}", d.body)
        assertArrayEquals(byteArrayOf(1, 2, 3), d.sig)
    }

    @Test fun garbageAndWrongProtocolAreRejected() {
        assertNull(MessageSerializer.decode("not json".toByteArray()))
        assertNull(MessageSerializer.decode(ByteArray(0)))
        assertNull(MessageSerializer.decode("{\"protocol\":\"OTHER\",\"version\":1}".toByteArray()))
        assertNull(MessageSerializer.decode(ByteArray(CommunicationProtocol.MAX_PACKET_BYTES + 1) { 'a'.code.toByte() }))
    }

    @Test fun statusValidation() {
        val ok = JSONObject().put("lat", 28.6).put("lon", 77.2).put("spd", 35).put("hdg", 120).put("acc", 8)
        assertNull(CommunicationProtocol.validateBody(MessageType.VEHICLE_STATUS, ok))
        assertTrue(CommunicationProtocol.validateBody(MessageType.VEHICLE_STATUS, JSONObject(ok.toString()).put("lat", 123)) != null)
        assertTrue(CommunicationProtocol.validateBody(MessageType.VEHICLE_STATUS, JSONObject(ok.toString()).put("spd", -4)) != null)
        assertTrue(CommunicationProtocol.validateBody(MessageType.VEHICLE_STATUS, JSONObject()) != null)
    }

    @Test fun otherBodiesValidated() {
        assertNull(CommunicationProtocol.validateBody(MessageType.LANE_CHANGE_REQUEST, JSONObject().put("dir", "LEFT")))
        assertTrue(CommunicationProtocol.validateBody(MessageType.LANE_CHANGE_REQUEST, JSONObject().put("dir", "UP")) != null)
        // optional "act": REQUEST/CANCEL accepted, anything else rejected, absent (older peers) still valid
        assertNull(CommunicationProtocol.validateBody(MessageType.LANE_CHANGE_REQUEST, JSONObject().put("dir", "RIGHT").put("act", "CANCEL")))
        assertNull(CommunicationProtocol.validateBody(MessageType.LANE_CHANGE_REQUEST, JSONObject().put("dir", "RIGHT").put("act", "REQUEST")))
        assertTrue(CommunicationProtocol.validateBody(MessageType.LANE_CHANGE_REQUEST, JSONObject().put("dir", "RIGHT").put("act", "STEER")) != null)
        assertTrue(CommunicationProtocol.validateBody(MessageType.EMERGENCY_ALERT, JSONObject().put("etype", "taxi")) != null)
        assertTrue(CommunicationProtocol.validateBody(MessageType.HAZARD_ALERT, JSONObject().put("kind", "ACCIDENT")) != null)
    }

    @Test fun geoDistanceAndDirection() {
        val d = Geo.distanceM(28.6139, 77.2090, 28.6139 + 0.001, 77.2090)
        assertEquals(111.0, d, 2.0)
        val b = Geo.bearingDeg(0.0, 0.0, 0.001, 0.0)
        assertEquals(0.0, b, 0.5)
        assertEquals("right", Geo.relativeDirection(0.0, 90.0))
        assertEquals("behind", Geo.relativeDirection(0.0, 180.0))
        assertEquals("left", Geo.relativeDirection(90.0, 0.0))
    }
}
