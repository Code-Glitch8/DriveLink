package com.drivelink.communication

import android.os.Handler
import android.os.Looper

interface PeerDelegate {
    fun onPeerConnected(endpointId: String)
    fun onPeerDisconnected(endpointId: String, sid: String?)
    fun onBytes(endpointId: String, bytes: ByteArray)
    fun onStatus(message: String)
}

/**
 * Decides which discovered endpoints to connect to. Both phones advertise and discover; the one with the lower
 * session name initiates (avoids simultaneous-request collisions), with a delayed fallback and a reconnect backoff.
 */
class DeviceDiscoveryManager(
    private val nearby: NearbyConnectionManager,
    private val repo: PeerConnectionRepository,
    private val localName: String,
    private val clock: () -> Long,
) : NearbyListener {
    var delegate: PeerDelegate? = null
    private val handler = Handler(Looper.getMainLooper())
    private val known = HashMap<String, String>()      // endpointId -> name
    private val requested = HashSet<String>()

    private fun compatible(name: String) = name.startsWith(CommunicationProtocol.NAME_PREFIX)

    fun start() { nearby.start(localName, this) }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        nearby.stop()
        known.clear(); requested.clear(); repo.clear()
    }

    private fun tryRequest(endpointId: String, force: Boolean) {
        val name = known[endpointId] ?: return
        if (repo.get(endpointId) != null || endpointId in requested) return
        if (repo.count() >= CommunicationProtocol.MAX_PEERS) return
        if (!force && localName >= name) return
        requested.add(endpointId)
        nearby.requestConnection(endpointId)
    }

    override fun onEndpointFound(endpointId: String, name: String) {
        if (!compatible(name)) return
        known[endpointId] = name
        tryRequest(endpointId, force = false)
        handler.postDelayed({ tryRequest(endpointId, force = true) }, 8_000)
    }

    override fun onEndpointLost(endpointId: String) { known.remove(endpointId); requested.remove(endpointId) }

    override fun onConnectionInitiated(endpointId: String, name: String) {
        if (compatible(name) && repo.count() < CommunicationProtocol.MAX_PEERS) nearby.accept(endpointId)
        else { nearby.reject(endpointId); requested.remove(endpointId) }
    }

    override fun onConnected(endpointId: String) {
        requested.remove(endpointId)
        repo.add(endpointId, clock())
        delegate?.onPeerConnected(endpointId)
        delegate?.onStatus("Connected to ${repo.count()} nearby vehicle(s)")
    }

    override fun onConnectionFailed(endpointId: String, statusCode: Int) {
        requested.remove(endpointId)
        if (known.containsKey(endpointId)) handler.postDelayed({ tryRequest(endpointId, force = true) }, 5_000)
    }

    override fun onDisconnected(endpointId: String) {
        val gone = repo.remove(endpointId)
        requested.remove(endpointId)
        delegate?.onPeerDisconnected(endpointId, gone?.identity?.sid)
        delegate?.onStatus("Searching… ${repo.count()} connected")
        if (known.containsKey(endpointId)) handler.postDelayed({ tryRequest(endpointId, force = true) }, 5_000) // reconnection
    }

    override fun onBytes(endpointId: String, bytes: ByteArray) { delegate?.onBytes(endpointId, bytes) }
    override fun onError(message: String) { delegate?.onStatus(message) }
}
