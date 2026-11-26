package org.simpmusic.wear

import android.content.Context

/** False in the FOSS build: no Play services, so there is no Wear Data Layer to talk to. */
fun isWearBridgeAvailable(): Boolean = false

fun interface WearMessageHandler {
    suspend fun onMessage(
        sourceNodeId: String,
        path: String,
        data: ByteArray,
    )
}

/** No-op twin of the Full build's WearBridge. */
object WearBridge {
    fun start(
        context: Context,
        handler: WearMessageHandler,
    ) = Unit

    suspend fun hasReachableNode(capability: String): Boolean = false

    suspend fun publish(
        path: String,
        bytes: Map<String, ByteArray>,
        assets: Map<String, ByteArray?>,
    ) = Unit

    suspend fun sendMessage(
        nodeId: String,
        path: String,
        data: ByteArray,
    ) = Unit
}
