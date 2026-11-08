package com.drivelink.communication

import java.util.concurrent.ConcurrentHashMap

class PeerConnection(val endpointId: String, val connectedAtMs: Long) {
    @Volatile var identity: PeerIdentity? = null
    @Volatile var lastSeq: Long = -1
    @Volatile var lastMessageMs: Long = connectedAtMs
    private val window = ArrayDeque<Long>()

    /** Sliding one-second window rate limit. */
    @Synchronized fun allow(nowMs: Long): Boolean {
        while (window.isNotEmpty() && nowMs - window.first() > 1000) window.removeFirst()
        if (window.size >= CommunicationProtocol.MAX_MESSAGES_PER_SECOND) return false
        window.addLast(nowMs)
        return true
    }
}

class PeerConnectionRepository {
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    @Volatile var invalidPackets = 0; private set
    @Volatile var droppedDuplicates = 0; private set
    @Volatile var rateLimited = 0; private set

    fun add(endpointId: String, nowMs: Long): PeerConnection = PeerConnection(endpointId, nowMs).also { peers[endpointId] = it }
    fun remove(endpointId: String): PeerConnection? = peers.remove(endpointId)
    fun get(endpointId: String): PeerConnection? = peers[endpointId]
    fun connectedIds(): List<String> = peers.keys.toList()
    fun count(): Int = peers.size
    fun clear() = peers.clear()

    fun countInvalid() { invalidPackets++ }
    fun countDuplicate() { droppedDuplicates++ }
    fun countRateLimited() { rateLimited++ }
}
