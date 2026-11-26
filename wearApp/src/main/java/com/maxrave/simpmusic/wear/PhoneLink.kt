package com.maxrave.simpmusic.wear

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.wear.protocol.WearCommand
import org.simpmusic.wear.protocol.WearNowPlaying
import org.simpmusic.wear.protocol.WearPaths
import org.simpmusic.wear.protocol.WearRequest
import org.simpmusic.wear.protocol.WearResponse
import org.simpmusic.wear.protocol.decodeNowPlaying
import org.simpmusic.wear.protocol.decodeResponse
import org.simpmusic.wear.protocol.encode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Where the watch stands with the phone. */
sealed interface PhoneState {
    data object Searching : PhoneState

    /** No reachable phone has the SimpMusic phone app (capability [WearPaths.PHONE_CAPABILITY]). */
    data object NoPhone : PhoneState

    data class Connected(
        val nodeId: String,
    ) : PhoneState
}

/**
 * The watch's end of the Data Layer. Reads the phone's `now_playing` DataItem (cached by Play
 * services, so the last state shows at once even before the phone answers), sends commands, and
 * asks the phone for library lists. Listeners are attached only while the activity is started.
 */
object PhoneLink {
    private const val TAG = "PhoneLink"
    private const val REQUEST_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context

    private val _phone = MutableStateFlow<PhoneState>(PhoneState.Searching)
    val phone: StateFlow<PhoneState> = _phone.asStateFlow()

    private val _nowPlaying = MutableStateFlow<WearNowPlaying?>(null)
    val nowPlaying: StateFlow<WearNowPlaying?> = _nowPlaying.asStateFlow()

    private val _artwork = MutableStateFlow<ImageBitmap?>(null)
    val artwork: StateFlow<ImageBitmap?> = _artwork.asStateFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<WearResponse.Result>>()
    private var artworkDigest: String? = null
    private var artworkJob: Job? = null

    private val dataListener =
        DataClient.OnDataChangedListener { events ->
            // The buffer is only valid inside this callback: read the map here, load the asset later.
            events
                .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearPaths.NOW_PLAYING }
                .map { DataMapItem.fromDataItem(it.dataItem).dataMap }
                .lastOrNull()
                ?.let { apply(it) }
        }

    private val messageListener =
        MessageClient.OnMessageReceivedListener { event ->
            if (event.path != WearPaths.RESPONSE) return@OnMessageReceivedListener
            val response = decodeResponse(event.data) ?: return@OnMessageReceivedListener
            pending.remove(response.id)?.complete(response.result)
        }

    fun attach(context: Context) {
        appContext = context.applicationContext
        Wearable.getDataClient(appContext).addListener(dataListener)
        Wearable.getMessageClient(appContext).addListener(messageListener)
        scope.launch {
            loadCachedState()
            refresh()
        }
    }

    fun detach() {
        if (!::appContext.isInitialized) return
        Wearable.getDataClient(appContext).removeListener(dataListener)
        Wearable.getMessageClient(appContext).removeListener(messageListener)
    }

    /** Finds the phone again and pings it, which also makes it republish now playing. */
    fun refresh() {
        scope.launch {
            _phone.value = PhoneState.Searching
            val node = findPhone()
            _phone.value = if (node == null) PhoneState.NoPhone else PhoneState.Connected(node)
            if (node != null) request(WearRequest.Body.Ping)
        }
    }

    fun send(command: WearCommand) {
        // Shown at once; the phone's next publish confirms or corrects it.
        _nowPlaying.value = _nowPlaying.value?.let { optimistic(it, command) }
        scope.launch {
            val node = phoneNode() ?: return@launch
            try {
                Wearable.getMessageClient(appContext).sendMessage(node, WearPaths.COMMAND, command.encode()).await()
            } catch (e: Exception) {
                Log.w(TAG, "command failed: ${e.message}")
            }
        }
    }

    /** Asks the phone and waits for its answer; null when the phone is unreachable or too slow. */
    suspend fun request(body: WearRequest.Body): WearResponse.Result? {
        val node = phoneNode() ?: return null
        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<WearResponse.Result>()
        pending[id] = answer
        return try {
            Wearable
                .getMessageClient(appContext)
                .sendMessage(node, WearPaths.REQUEST, WearRequest(id, body).encode())
                .await()
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { answer.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.message}")
            null
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Opens SimpMusic on the phone. Android 12+ will not let the phone app start its player from the
     * background, so this is how the watch gets a stopped phone app going: Wear OS asks on the phone.
     */
    fun openOnPhone(link: String = "simpmusic://wear") {
        val node = (phone.value as? PhoneState.Connected)?.nodeId
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(link))
                .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            RemoteActivityHelper(appContext).startRemoteActivity(intent, node)
        } catch (e: Exception) {
            Log.w(TAG, "open on phone failed: ${e.message}")
        }
    }

    private suspend fun phoneNode(): String? =
        (phone.value as? PhoneState.Connected)?.nodeId ?: findPhone()?.also { _phone.value = PhoneState.Connected(it) }

    private suspend fun findPhone(): String? =
        try {
            val nodes =
                Wearable
                    .getCapabilityClient(appContext)
                    .getCapability(WearPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                    .await()
                    .nodes
            (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
        } catch (e: Exception) {
            Log.w(TAG, "finding the phone failed: ${e.message}")
            null
        }

    private suspend fun loadCachedState() {
        try {
            val uri = Uri.Builder().scheme("wear").path(WearPaths.NOW_PLAYING).build()
            val buffer = Wearable.getDataClient(appContext).getDataItems(uri).await()
            val map =
                try {
                    buffer.lastOrNull()?.let { DataMapItem.fromDataItem(it).dataMap }
                } finally {
                    buffer.release()
                }
            map?.let { apply(it) }
        } catch (e: Exception) {
            Log.w(TAG, "reading the cached state failed: ${e.message}")
        }
    }

    private fun apply(map: DataMap) {
        map.getByteArray(WearPaths.KEY_STATE)?.let { decodeNowPlaying(it) }?.let { _nowPlaying.value = it }
        val asset = map.getAsset(WearPaths.KEY_ARTWORK)
        if (asset == null) {
            artworkDigest = null
            _artwork.value = null
        } else if (asset.digest != artworkDigest) {
            loadArtwork(asset)
        }
    }

    private fun loadArtwork(asset: Asset) {
        artworkJob?.cancel()
        artworkJob =
            scope.launch {
                try {
                    val stream = Wearable.getDataClient(appContext).getFdForAsset(asset).await().inputStream
                    val bitmap = stream.use { BitmapFactory.decodeStream(it) }
                    artworkDigest = asset.digest
                    _artwork.value = bitmap?.asImageBitmap()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "loading the artwork failed: ${e.message}")
                }
            }
    }

    private fun optimistic(
        state: WearNowPlaying,
        command: WearCommand,
    ): WearNowPlaying {
        val now = System.currentTimeMillis()
        return when (command) {
            WearCommand.PlayPause ->
                state.copy(
                    isPlaying = !state.isPlaying,
                    positionMs = positionAt(state, now),
                    positionAtEpochMs = now,
                )
            WearCommand.ToggleLike -> state.copy(isLiked = !state.isLiked)
            WearCommand.Shuffle -> state.copy(isShuffle = !state.isShuffle)
            is WearCommand.SeekTo -> state.copy(positionMs = command.positionMs, positionAtEpochMs = now)
            else -> state
        }
    }
}

/** Where playback is now, carried forward from the phone's last report. */
fun positionAt(
    state: WearNowPlaying,
    now: Long = System.currentTimeMillis(),
): Long {
    val moved = if (state.isPlaying && state.sessionActive) now - state.positionAtEpochMs else 0L
    val position = state.positionMs + moved.coerceAtLeast(0L)
    return if (state.durationMs > 0) position.coerceAtMost(state.durationMs) else position
}
