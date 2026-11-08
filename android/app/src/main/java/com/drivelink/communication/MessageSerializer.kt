package com.drivelink.communication

import java.util.Base64
import org.json.JSONObject

/** Wire envelope. `body` is a JSON object serialised as a string so the signature covers exact bytes. */
class Envelope(
    val type: MessageType,
    val sid: String,
    val seq: Long,
    val tsSec: Long,
    val body: String,
    val sig: ByteArray = ByteArray(0),
)

object MessageSerializer {
    fun signingInput(e: Envelope): ByteArray =
        "${CommunicationProtocol.PROTOCOL}|${CommunicationProtocol.VERSION}|${e.type.name}|${e.sid}|${e.seq}|${e.tsSec}|${e.body}"
            .toByteArray(Charsets.UTF_8)

    fun encode(e: Envelope): ByteArray {
        val o = JSONObject()
        o.put("protocol", CommunicationProtocol.PROTOCOL)
        o.put("version", CommunicationProtocol.VERSION)
        o.put("type", e.type.name)
        o.put("sid", e.sid)
        o.put("seq", e.seq)
        o.put("ts", e.tsSec)
        o.put("body", e.body)
        o.put("sig", Base64.getEncoder().encodeToString(e.sig))
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    /** Returns null for anything malformed, oversized, wrong protocol/version or unknown type. */
    fun decode(bytes: ByteArray): Envelope? {
        if (bytes.isEmpty() || bytes.size > CommunicationProtocol.MAX_PACKET_BYTES) return null
        return try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            if (o.optString("protocol") != CommunicationProtocol.PROTOCOL) return null
            if (o.optInt("version", -1) != CommunicationProtocol.VERSION) return null
            val type = MessageType.valueOf(o.getString("type"))
            val sid = o.getString("sid")
            if (sid.length !in 4..32) return null
            Envelope(type, sid, o.getLong("seq"), o.getLong("ts"), o.getString("body"),
                Base64.getDecoder().decode(o.optString("sig")))
        } catch (_: Exception) {
            null
        }
    }
}
