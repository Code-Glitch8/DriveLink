package com.drivelink

import com.drivelink.communication.CredentialVerifier
import com.drivelink.communication.PeerConnectionRepository
import com.drivelink.communication.PeerEvent
import com.drivelink.communication.SessionKeys
import com.drivelink.communication.VehicleAuthenticationManager
import com.drivelink.communication.VehicleDataManager
import com.drivelink.location.LocationSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class AuthenticationTest {
    private val now = 1_700_000_000_000L

    private class Side(sid: String, now: Long, verifier: CredentialVerifier = CredentialVerifier("")) {
        val keys = SessionKeys()
        val repo = PeerConnectionRepository()
        val events = ArrayList<PeerEvent>()
        val dm = VehicleDataManager(sid, keys, VehicleAuthenticationManager(verifier), repo, { now }) { events.add(it) }
    }

    private val sample = LocationSample(28.6, 77.2, 35.0, 120.0, 6.0, now)

    @Test fun statusIsIgnoredUntilHelloAuthenticates() {
        val a = Side("DL1-aaaa1111", now); val b = Side("DL1-bbbb2222", now)
        b.repo.add("epA", now)
        b.dm.onBytes("epA", a.dm.status(sample))
        assertTrue(b.events.isEmpty())
        b.dm.onBytes("epA", a.dm.hello("DL-IND-000001", "car", null))
        b.dm.onBytes("epA", a.dm.status(sample))
        assertTrue(b.events.any { it is PeerEvent.Status })
    }

    @Test fun duplicateAndTamperedMessagesAreDropped() {
        val a = Side("DL1-aaaa1111", now); val b = Side("DL1-bbbb2222", now)
        b.repo.add("epA", now)
        b.dm.onBytes("epA", a.dm.hello("DL-IND-000001", "car", null))
        val msg = a.dm.status(sample)
        b.dm.onBytes("epA", msg)
        b.dm.onBytes("epA", msg) // replay
        assertEquals(1, b.events.count { it is PeerEvent.Status })
        assertEquals(1, b.repo.droppedDuplicates)

        val tampered = String(a.dm.status(sample)).replace("28.6", "29.6").toByteArray()
        b.dm.onBytes("epA", tampered)
        assertEquals(1, b.events.count { it is PeerEvent.Status })
    }

    @Test fun otherSessionCannotInjectOnAnAuthenticatedEndpoint() {
        val a = Side("DL1-aaaa1111", now); val evil = Side("DL1-eeee9999", now); val b = Side("DL1-bbbb2222", now)
        b.repo.add("epA", now)
        b.dm.onBytes("epA", a.dm.hello("DL-IND-000001", "car", null))
        b.dm.onBytes("epA", evil.dm.status(sample))
        assertTrue(b.events.none { it is PeerEvent.Status })
    }

    @Test fun emergencyClaimWithoutCredentialIsUnverified() {
        val a = Side("DL1-aaaa1111", now); val b = Side("DL1-bbbb2222", now)
        b.repo.add("epA", now)
        b.dm.onBytes("epA", a.dm.hello("DL-IND-000001", "ambulance", null))
        b.dm.onBytes("epA", a.dm.emergency("ambulance"))
        val e = b.events.filterIsInstance<PeerEvent.Emergency>().single()
        assertEquals(false, e.verified)
    }

    @Test fun serverSignedCredentialMakesEmergencyVerified() {
        val server = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val payload = JSONObject().put("v", 1).put("vid", "DL-IND-000007").put("etype", "ambulance").put("exp", now / 1000 + 3600).toString().toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(server.private); update(payload) }.sign()
        val p1363 = derToP1363(sig)
        val verifier = CredentialVerifier(Base64.getEncoder().encodeToString(server.public.encoded))
        val cred = Base64.getEncoder().encodeToString(payload) to Base64.getEncoder().encodeToString(p1363)

        val a = Side("DL1-aaaa1111", now); val b = Side("DL1-bbbb2222", now, verifier)
        b.repo.add("epA", now)
        b.dm.onBytes("epA", a.dm.hello("DL-IND-000007", "ambulance", cred))
        b.dm.onBytes("epA", a.dm.emergency("ambulance"))
        assertTrue(b.events.filterIsInstance<PeerEvent.Emergency>().single().verified)

        // The same credential presented by a different vehicle ID must not verify.
        val c = Side("DL1-cccc3333", now); val d = Side("DL1-dddd4444", now, verifier)
        d.repo.add("epC", now)
        d.dm.onBytes("epC", c.dm.hello("DL-IND-000099", "ambulance", cred))
        d.dm.onBytes("epC", c.dm.emergency("ambulance"))
        assertEquals(false, d.events.filterIsInstance<PeerEvent.Emergency>().single().verified)
    }

    private fun derToP1363(der: ByteArray): ByteArray {
        var i = 2
        fun readInt(): ByteArray {
            require(der[i].toInt() == 0x02); val len = der[i + 1].toInt() and 0xff
            val raw = der.copyOfRange(i + 2, i + 2 + len); i += 2 + len
            val trimmed = raw.dropWhile { it.toInt() == 0 }.toByteArray()
            return ByteArray(32 - trimmed.size) + trimmed
        }
        return readInt() + readInt()
    }
}
