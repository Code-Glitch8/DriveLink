package com.drivelink.communication

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Ephemeral per-journey signing key. Peers learn the public half in the HELLO message. */
class SessionKeys {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val publicKeyB64: String = Base64.getEncoder().encodeToString(pair.public.encoded)

    fun sign(data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(pair.private)
        s.update(data)
        return s.sign()
    }
}

object SignatureUtil {
    fun verify(publicKeyB64: String, data: ByteArray, derSignature: ByteArray): Boolean = try {
        val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pk)
        s.update(data)
        s.verify(derSignature)
    } catch (_: Exception) {
        false
    }

    /** Converts a 64-byte IEEE P1363 (r||s) signature to ASN.1 DER. */
    fun p1363ToDer(sig: ByteArray): ByteArray {
        require(sig.size == 64) { "bad signature length" }
        fun part(b: ByteArray): ByteArray {
            val bytes = BigInteger(1, b).toByteArray()
            return byteArrayOf(0x02, bytes.size.toByte()) + bytes
        }
        val body = part(sig.copyOfRange(0, 32)) + part(sig.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

data class Credential(val vid: String, val etype: String, val expSec: Long)

/** Verifies server-signed emergency credentials offline using the configured public key. */
class CredentialVerifier(private val publicKeyB64: String) {
    val configured: Boolean get() = publicKeyB64.isNotBlank()

    fun verify(payloadB64: String, sigB64: String, nowSec: Long): Credential? {
        if (!configured) return null
        return try {
            val payload = Base64.getDecoder().decode(payloadB64)
            val der = SignatureUtil.p1363ToDer(Base64.getDecoder().decode(sigB64))
            if (!SignatureUtil.verify(publicKeyB64, payload, der)) return null
            val o = JSONObject(String(payload, Charsets.UTF_8))
            val c = Credential(o.getString("vid"), o.getString("etype"), o.getLong("exp"))
            if (c.expSec < nowSec || c.etype !in CommunicationProtocol.EMERGENCY_TYPES) null else c
        } catch (_: Exception) {
            null
        }
    }
}

data class PeerIdentity(
    val sid: String,
    val publicKeyB64: String,
    val vehicleId: String,
    val vehicleType: String,
    val credential: Credential?,
    val verifiedAtMs: Long,
)

/**
 * Peer authentication. A HELLO proves the sender controls the session key it announces (self-signed), binds that
 * key to the session id, and may carry a server-signed emergency credential. Nothing is trusted because of a
 * previous connection: identities live only for the current connection and expire unless HELLO is refreshed.
 */
class VehicleAuthenticationManager(private val verifier: CredentialVerifier) {
    companion object { const val IDENTITY_TTL_MS = 5 * 60_000L }

    fun buildHelloBody(keys: SessionKeys, vehicleId: String, vehicleType: String, cred: Pair<String, String>?): String {
        val o = JSONObject()
        o.put("pub", keys.publicKeyB64)
        o.put("vid", vehicleId)
        o.put("vtype", vehicleType)
        if (cred != null) o.put("cp", cred.first).put("cs", cred.second)
        return o.toString()
    }

    fun verifyHello(env: Envelope, nowMs: Long): PeerIdentity? {
        if (env.type != MessageType.HELLO) return null
        val body = try { JSONObject(env.body) } catch (_: Exception) { return null }
        if (CommunicationProtocol.validateBody(MessageType.HELLO, body) != null) return null
        val pub = body.getString("pub")
        if (!SignatureUtil.verify(pub, MessageSerializer.signingInput(env), env.sig)) return null
        val vid = body.optString("vid")
        var cred: Credential? = null
        if (body.has("cp") && body.has("cs")) {
            cred = verifier.verify(body.optString("cp"), body.optString("cs"), nowMs / 1000)
            if (cred != null && cred.vid != vid) cred = null // credential must be for the vehicle claimed
        }
        return PeerIdentity(env.sid, pub, vid, body.optString("vtype", "other"), cred, nowMs)
    }

    fun verifyMessage(id: PeerIdentity, env: Envelope, nowMs: Long): Boolean =
        env.sid == id.sid && nowMs - id.verifiedAtMs <= IDENTITY_TTL_MS &&
            SignatureUtil.verify(id.publicKeyB64, MessageSerializer.signingInput(env), env.sig)
}
