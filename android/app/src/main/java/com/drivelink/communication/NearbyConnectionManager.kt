package com.drivelink.communication

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

interface NearbyListener {
    fun onEndpointFound(endpointId: String, name: String)
    fun onEndpointLost(endpointId: String)
    fun onConnectionInitiated(endpointId: String, name: String)
    fun onConnected(endpointId: String)
    fun onConnectionFailed(endpointId: String, statusCode: Int)
    fun onDisconnected(endpointId: String)
    fun onBytes(endpointId: String, bytes: ByteArray)
    fun onError(message: String)
}

/** Thin transport wrapper around Google Nearby Connections. The strategy can be swapped before start(). */
class NearbyConnectionManager(context: Context, var strategy: Strategy = Strategy.P2P_CLUSTER) {
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    @Volatile private var listener: NearbyListener? = null
    private var localName = ""

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            listener?.onConnectionInitiated(endpointId, info.endpointName)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) listener?.onConnected(endpointId)
            else listener?.onConnectionFailed(endpointId, result.status.statusCode)
        }
        override fun onDisconnected(endpointId: String) { listener?.onDisconnected(endpointId) }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { listener?.onBytes(endpointId, it) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            listener?.onEndpointFound(endpointId, info.endpointName)
        }
        override fun onEndpointLost(endpointId: String) { listener?.onEndpointLost(endpointId) }
    }

    fun start(name: String, l: NearbyListener) {
        listener = l
        localName = name
        client.startAdvertising(name, CommunicationProtocol.SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { l.onError("Advertising failed: ${it.message}") }
        client.startDiscovery(CommunicationProtocol.SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { l.onError("Discovery failed: ${it.message}") }
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        listener = null
    }

    fun requestConnection(endpointId: String) {
        client.requestConnection(localName, endpointId, lifecycle)
            .addOnFailureListener { listener?.onConnectionFailed(endpointId, -1) }
    }
    fun accept(endpointId: String) { client.acceptConnection(endpointId, payloads) }
    fun reject(endpointId: String) { client.rejectConnection(endpointId) }
    fun disconnect(endpointId: String) { client.disconnectFromEndpoint(endpointId) }

    fun send(endpointIds: List<String>, bytes: ByteArray) {
        if (endpointIds.isEmpty()) return
        client.sendPayload(endpointIds, Payload.fromBytes(bytes))
            .addOnFailureListener { listener?.onError("Send failed: ${it.message}") }
    }
}
