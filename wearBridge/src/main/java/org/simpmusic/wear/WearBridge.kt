package org.simpmusic.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** True in the Full build, where this module talks to the Wear Data Layer. */
fun isWearBridgeAvailable(): Boolean = true

/** Receives what a watch sends under /simpmusic. Runs off the main thread. */
fun interface WearMessageHandler {
    suspend fun onMessage(
        sourceNodeId: String,
        path: String,
        data: ByteArray,
    )
}

/**
 * The phone's end of the Data Layer. Carries bytes only: what they mean is decided by the caller
 * (composeApp's WearPhoneLink), so this module needs no app code and the FOSS twin can stay empty.
 */
object WearBridge {
    private const val TAG = "WearBridge"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    internal var handler: WearMessageHandler? = null

    /** Call from Application.onCreate, before any message can arrive: Play services starts the service after it. */
    fun start(
        context: Context,
        handler: WearMessageHandler,
    ) {
        appContext = context.applicationContext
        this.handler = handler
    }

    /** Whether a watch that has the SimpMusic watch app installed is reachable right now. */
    suspend fun hasReachableNode(capability: String): Boolean {
        val context = appContext ?: return false
        return try {
            Wearable
                .getCapabilityClient(context)
                .getCapability(capability, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
                .isNotEmpty()
        } catch (e: Exception) {
            // No Play services, or the Wear OS app was never installed: there is no watch.
            false
        }
    }

    /**
     * Replaces the DataItem at [path]. [bytes] go in as byte arrays, [assets] as Assets (deduplicated
     * by Play services, so republishing the same cover costs nothing). Urgent, so the watch sees it at once.
     */
    suspend fun publish(
        path: String,
        bytes: Map<String, ByteArray>,
        assets: Map<String, ByteArray?>,
    ) {
        val context = appContext ?: return
        try {
            val request =
                PutDataMapRequest.create(path).apply {
                    bytes.forEach { (key, value) -> dataMap.putByteArray(key, value) }
                    assets.forEach { (key, value) ->
                        if (value != null) dataMap.putAsset(key, Asset.createFromBytes(value)) else dataMap.remove(key)
                    }
                }
            Wearable
                .getDataClient(context)
                .putDataItem(request.asPutDataRequest().setUrgent())
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "publish $path failed: ${e.message}")
        }
    }

    suspend fun sendMessage(
        nodeId: String,
        path: String,
        data: ByteArray,
    ) {
        val context = appContext ?: return
        try {
            Wearable.getMessageClient(context).sendMessage(nodeId, path, data).await()
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage $path failed: ${e.message}")
        }
    }
}

/**
 * Started by Play services for every message under /simpmusic, even with the app process dead
 * (the Application, and with it [WearBridge.start], runs first). The callback runs on a binder
 * worker thread, so blocking it while the handler works keeps the service alive until the answer
 * is sent.
 */
class WearBridgeService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val handler = WearBridge.handler ?: return
        runBlocking {
            withTimeoutOrNull(HANDLER_TIMEOUT_MS) {
                try {
                    handler.onMessage(messageEvent.sourceNodeId, messageEvent.path, messageEvent.data)
                } catch (e: Exception) {
                    Log.w("WearBridge", "handler failed for ${messageEvent.path}: ${e.message}")
                }
            }
        }
    }

    private companion object {
        const val HANDLER_TIMEOUT_MS = 20_000L
    }
}
