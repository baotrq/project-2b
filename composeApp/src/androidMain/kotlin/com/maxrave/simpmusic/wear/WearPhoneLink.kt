package com.maxrave.simpmusic.wear

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import androidx.core.content.ContextCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.maxrave.common.Config
import com.maxrave.common.LOCAL_PLAYLIST_ID
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.SongRepository
import com.maxrave.domain.utils.toListName
import com.maxrave.domain.utils.toTrack
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.simpmusic.wear.WearBridge
import org.simpmusic.wear.isWearBridgeAvailable
import org.simpmusic.wear.protocol.WEAR_MAX_LIST_SONGS
import org.simpmusic.wear.protocol.WEAR_MAX_UP_NEXT
import org.simpmusic.wear.protocol.WearCollection
import org.simpmusic.wear.protocol.WearCollections
import org.simpmusic.wear.protocol.WearCommand
import org.simpmusic.wear.protocol.WearNowPlaying
import org.simpmusic.wear.protocol.WearPaths
import org.simpmusic.wear.protocol.WearRequest
import org.simpmusic.wear.protocol.WearResponse
import org.simpmusic.wear.protocol.WearTrack
import org.simpmusic.wear.protocol.decodeCommand
import org.simpmusic.wear.protocol.decodeRequest
import org.simpmusic.wear.protocol.encode
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.downloaded
import simpmusic.composeapp.generated.resources.favorite
import simpmusic.composeapp.generated.resources.most_played
import simpmusic.composeapp.generated.resources.recently
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * The phone's side of the Wear OS remote. Keeps the `now_playing` DataItem current for the watch
 * app, and answers its commands (transport, like, volume, queue jumps) and requests (library lists,
 * play a collection) through [MediaPlayerHandler] — the same calls the phone's own UI makes.
 *
 * Publishes only on state changes (track, play/pause, like, shuffle, repeat, queue, a seek) and only
 * while a watch with the SimpMusic watch app is reachable; the watch carries the position forward
 * itself. Idle on FOSS builds, where the bridge is a no-op.
 */
object WearPhoneLink : KoinComponent {
    private const val TAG = "Wear"
    private const val ARTWORK_SIZE = 320

    /** How long a pick from the watch waits for the phone app to be opened. */
    private const val PENDING_PLAY_TIMEOUT_MS = 60_000L

    /** Wait after the player service appears, so the app's own launch restore lands first. */
    private const val PENDING_PLAY_SETTLE_MS = 1_500L

    /** A position report this far from where the last publish says playback should be means a seek. */
    private const val DRIFT_REPUBLISH_MS = 2_500L

    private val handler: MediaPlayerHandler by inject()
    private val songRepository: SongRepository by inject()
    private val localPlaylistRepository: LocalPlaylistRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val publishSignal = Channel<Unit>(Channel.CONFLATED)
    private lateinit var appContext: Context

    @Volatile
    private var lastPublished: WearNowPlaying? = null

    /**
     * The position the phone's own UI shows, and the media item it belongs to. Preferred over
     * `getProgress()`, which during a crossfade (and for a moment after any track change) still reads
     * the outgoing player — that is how the watch's ring kept the previous song's progress.
     */
    @Volatile
    private var lastProgress: Pair<String, Long>? = null
    private var artworkUrl: String? = null
    private var artworkBytes: ByteArray? = null

    fun start(context: Context) {
        if (!isWearBridgeAvailable()) return
        appContext = context.applicationContext
        WearBridge.start(appContext) { node, path, data -> onMessage(node, path, data) }
        scope.launch { publishLoop() }
        scope.launch { observeState() }
        scope.launch { observeSeeks() }
        observeVolume()
    }

    /**
     * The phone's own volume buttons (and the watch's crown) change STREAM_MUSIC without touching any
     * player state, so the watch's volume arc would show a stale level. VOLUME_CHANGED_ACTION is not a
     * public API constant, but it is the system broadcast AudioService sends on every change.
     */
    private fun observeVolume() {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) requestPublish()
                }
            }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    private fun requestPublish() {
        publishSignal.trySend(Unit)
    }

    // ---- Publishing ----

    private suspend fun publishLoop() {
        for (signal in publishSignal) {
            // Coalesces the burst a track change makes (media item, queue index, duration, like).
            delay(250)
            try {
                publishNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Publishing now playing failed: ${e.message}")
            }
        }
    }

    private suspend fun observeState() {
        combine(
            handler.nowPlayingState.map { it.mediaItem.mediaId to it.songEntity?.videoId },
            handler.controlState.map { listOf(it.isPlaying, it.isLiked, it.isShuffle, it.repeatState, it.isCrossfading) },
            handler.queueData.map { it?.data?.listTracks?.size to it?.data?.playlistName },
            handler.currentSongIndex,
            handler.simpleMediaState
                .filterIsInstance<SimpleMediaState.Ready>()
                .map { it.duration }
                .onStart { emit(0L) },
        ) { a, b, c, d, e -> listOf(a, b, c, d, e) }
            .distinctUntilChanged()
            .collect { requestPublish() }
    }

    /**
     * Records the position the phone's UI shows, and republishes when it strays from what the watch was
     * told: a seek on the phone, or the first real position of a new track (whose publish may have gone
     * out before the player had switched over).
     */
    private suspend fun observeSeeks() {
        handler.simpleMediaState.filterIsInstance<SimpleMediaState.Progress>().collect { progress ->
            val mediaId = handler.nowPlayingState.value.mediaItem.mediaId
            lastProgress = mediaId to progress.progress
            val last = lastPublished ?: return@collect
            if (!last.sessionActive || last.track == null) return@collect
            val expected =
                if (last.isPlaying) {
                    last.positionMs + (System.currentTimeMillis() - last.positionAtEpochMs)
                } else {
                    last.positionMs
                }
            if (abs(progress.progress - expected) > DRIFT_REPUBLISH_MS) requestPublish()
        }
    }

    private suspend fun publishNow() {
        if (!WearBridge.hasReachableNode(WearPaths.WATCH_CAPABILITY)) return
        val state = withContext(Dispatchers.Main) { buildState() }
        val artwork = artworkFor(state.track?.thumbnail)
        WearBridge.publish(
            path = WearPaths.NOW_PLAYING,
            bytes = mapOf(WearPaths.KEY_STATE to state.encode()),
            assets = mapOf(WearPaths.KEY_ARTWORK to artwork),
        )
        lastPublished = state
    }

    /** Reads the player, so it runs on the main thread like every other caller of the handler. */
    private fun buildState(): WearNowPlaying {
        val now = System.currentTimeMillis()
        val sessionActive = isPlayerServiceRunning()
        val nowPlaying = handler.nowPlayingState.value
        val song = nowPlaying.songEntity
        val control = handler.controlState.value
        val tracks = handler.queueData.value?.data?.listTracks.orEmpty()
        // The media item switches first; its SongEntity is loaded a moment later. Until then
        // songEntity is still the PREVIOUS song, so use it only when it belongs to the current item.
        val mediaVideoId = nowPlaying.mediaItem.mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO)
        val currentSong = song?.takeIf { it.videoId == mediaVideoId }
        val queueTrack = nowPlaying.track?.takeIf { it.videoId == mediaVideoId }
        val currentId = currentSong?.videoId ?: queueTrack?.videoId
        val queueIndex = queueIndexOf(tracks, currentId)
        val metadata = nowPlaying.mediaItem.metadata
        val track =
            currentSong?.toWearTrack()
                ?: queueTrack?.toWearTrack()
                // Neither has caught up yet: the media item's own tags, so the watch never flashes "Nothing playing".
                ?: metadata.title?.takeIf { mediaVideoId.isNotBlank() }?.let {
                    WearTrack(mediaVideoId, it, metadata.artist.orEmpty(), metadata.artworkUri)
                }
        val durationSeconds = currentSong?.durationSeconds ?: queueTrack?.durationSeconds ?: 0
        return WearNowPlaying(
            sessionActive = sessionActive,
            track = track,
            isPlaying = control.isPlaying,
            isLiked = control.isLiked,
            isShuffle = control.isShuffle,
            isCrossfading = control.isCrossfading,
            repeatMode =
                when (control.repeatState) {
                    RepeatState.None -> 0
                    RepeatState.One -> 1
                    RepeatState.All -> 2
                },
            positionMs = if (track != null) currentPosition(nowPlaying.mediaItem.mediaId) else 0L,
            // The song's own length first: the player's can still be the outgoing track's mid-crossfade.
            durationMs =
                when {
                    track == null -> 0L
                    durationSeconds > 0 -> durationSeconds * 1000L
                    else -> handler.getPlayerDuration().coerceAtLeast(0L)
                },
            positionAtEpochMs = now,
            volume = musicVolume(),
            queueIndex = queueIndex,
            upNext =
                if (queueIndex >= 0) {
                    tracks.drop(queueIndex + 1).take(WEAR_MAX_UP_NEXT).map { it.toWearTrack() }
                } else {
                    emptyList()
                },
            queueName = handler.queueData.value?.data?.playlistName,
            updatedAt = now,
        )
    }

    /**
     * Where the current track is. The UI's last reported position when it belongs to this media item;
     * right after a track change there is none yet, and the new track is at its start, so 0 — never the
     * outgoing player's position.
     */
    private fun currentPosition(mediaId: String): Long {
        val progress = lastProgress
        return when {
            progress != null && progress.first == mediaId -> progress.second.coerceAtLeast(0L)
            progress != null -> 0L
            else -> handler.getProgress().coerceAtLeast(0L)
        }
    }

    /**
     * The playing track's position in `listTracks`, which is in play order (shuffled order while
     * shuffle is on) — the same positions `playMediaItemInMediaSource` takes. `currentSongIndex` is
     * the player's own index, which only matches while shuffle is off, so it is used as the hint
     * among repeated ids rather than as the answer.
     */
    private fun queueIndexOf(
        tracks: List<Track>,
        videoId: String?,
    ): Int {
        if (videoId == null || tracks.isEmpty()) return -1
        val hint = handler.currentSongIndex.value
        if (hint in tracks.indices && tracks[hint].videoId == videoId) return hint
        return tracks.indexOfFirst { it.videoId == videoId }
    }

    private suspend fun artworkFor(url: String?): ByteArray? {
        if (url == null) return null
        if (url == artworkUrl) return artworkBytes
        val bytes =
            try {
                val request =
                    ImageRequest
                        .Builder(appContext)
                        .data(url)
                        .size(ARTWORK_SIZE)
                        .allowHardware(false)
                        .build()
                val bitmap = SingletonImageLoader.get(appContext).execute(request).image?.toBitmap()
                bitmap?.let {
                    ByteArrayOutputStream().use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.toByteArray()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "Artwork for the watch failed: ${e.message}")
                null
            }
        artworkUrl = url
        artworkBytes = bytes
        return bytes
    }

    // ---- Incoming ----

    private suspend fun onMessage(
        node: String,
        path: String,
        data: ByteArray,
    ) {
        when (path) {
            WearPaths.COMMAND -> decodeCommand(data)?.let { onCommand(it) }
            WearPaths.REQUEST -> {
                val request = decodeRequest(data) ?: return
                val result =
                    try {
                        onRequest(request.body)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(TAG, "Watch request failed: ${e.message}")
                        WearResponse.Result.Error(e.message ?: "error")
                    }
                WearBridge.sendMessage(node, WearPaths.RESPONSE, WearResponse(request.id, result).encode())
            }
        }
    }

    private suspend fun onCommand(command: WearCommand) {
        Logger.d(TAG, "Watch command: $command")
        when (command) {
            WearCommand.VolumeUp -> adjustVolume(AudioManager.ADJUST_RAISE)
            WearCommand.VolumeDown -> adjustVolume(AudioManager.ADJUST_LOWER)
            is WearCommand.SetVolume -> setVolume(command.level)
            else -> {
                // Without the player service there is no session to control: the watch greys its
                // buttons out in that state, so anything arriving here is stale.
                if (!isPlayerServiceRunning()) return
                withContext(Dispatchers.Main) {
                    when (command) {
                        WearCommand.PlayPause -> handler.onPlayerEvent(PlayerEvent.PlayPause)
                        WearCommand.Next -> handler.onPlayerEvent(PlayerEvent.Next)
                        WearCommand.Previous -> handler.onPlayerEvent(PlayerEvent.Previous)
                        WearCommand.ToggleLike -> handler.onPlayerEvent(PlayerEvent.ToggleLike)
                        WearCommand.Shuffle -> handler.onPlayerEvent(PlayerEvent.Shuffle)
                        WearCommand.Repeat -> handler.onPlayerEvent(PlayerEvent.Repeat)
                        is WearCommand.SeekTo -> {
                            val duration = handler.getPlayerDuration()
                            if (duration > 0L) {
                                val percent = command.positionMs.coerceIn(0L, duration) * 100f / duration
                                handler.onPlayerEvent(PlayerEvent.UpdateProgress(percent))
                            }
                        }
                        is WearCommand.PlayQueueItem -> {
                            val size = handler.queueData.value?.data?.listTracks?.size ?: 0
                            if (command.queueIndex in 0 until size) handler.playMediaItemInMediaSource(command.queueIndex)
                        }
                        else -> Unit
                    }
                }
            }
        }
        requestPublish()
    }

    private suspend fun onRequest(body: WearRequest.Body): WearResponse.Result =
        when (body) {
            WearRequest.Body.Ping -> {
                // The watch opened: make sure it sees the current state, not one left from earlier.
                requestPublish()
                WearResponse.Result.Pong(serviceRunning = isPlayerServiceRunning())
            }
            WearRequest.Body.Library -> WearResponse.Result.Library(libraryCollections())
            is WearRequest.Body.Collection -> {
                val (title, songs) = collection(body.collectionId)
                WearResponse.Result.Songs(title, songs.take(WEAR_MAX_LIST_SONGS).map { it.toWearTrack() })
            }
            is WearRequest.Body.Play -> play(body)
        }

    private suspend fun libraryCollections(): List<WearCollection> {
        val liked = songRepository.getLikedSongs().first()
        val downloaded = songRepository.getDownloadedSongs().first().orEmpty()
        val mostPlayed = songRepository.getMostPlayedSongs().first()
        val recent = songRepository.getRecentSong(WEAR_MAX_LIST_SONGS, 0)
        val fixed =
            listOf(
                WearCollection(WearCollections.FAVORITES, getString(Res.string.favorite), liked.size, liked.firstOrNull()?.thumbnails),
                WearCollection(WearCollections.DOWNLOADED, getString(Res.string.downloaded), downloaded.size, downloaded.firstOrNull()?.thumbnails),
                WearCollection(WearCollections.MOST_PLAYED, getString(Res.string.most_played), mostPlayed.size, mostPlayed.firstOrNull()?.thumbnails),
                WearCollection(WearCollections.RECENT, getString(Res.string.recently), recent.size, recent.firstOrNull()?.thumbnails),
            ).filter { it.count > 0 }
        val playlists =
            localPlaylistRepository.getAllLocalPlaylists().first().map {
                WearCollection(WearCollections.LOCAL_PREFIX + it.id, it.title, it.tracks?.size ?: 0, it.thumbnail)
            }
        return fixed + playlists
    }

    /** Name and songs of [id], in the order the phone's own screen lists them. */
    private suspend fun collection(id: String): Pair<String, List<SongEntity>> =
        when (id) {
            WearCollections.FAVORITES -> getString(Res.string.favorite) to songRepository.getLikedSongs().first()
            WearCollections.DOWNLOADED -> getString(Res.string.downloaded) to songRepository.getDownloadedSongs().first().orEmpty()
            WearCollections.MOST_PLAYED -> getString(Res.string.most_played) to songRepository.getMostPlayedSongs().first()
            WearCollections.RECENT -> getString(Res.string.recently) to songRepository.getRecentSong(WEAR_MAX_LIST_SONGS, 0)
            else -> {
                val playlistId = id.removePrefix(WearCollections.LOCAL_PREFIX).toLongOrNull()
                val playlist = playlistId?.let { localPlaylistRepository.getLocalPlaylist(it).first().data }
                val ids = playlist?.tracks.orEmpty()
                (playlist?.title ?: "") to if (ids.isEmpty()) emptyList() else songRepository.getSongsByListVideoId(ids).first()
            }
        }

    private suspend fun play(body: WearRequest.Body.Play): WearResponse.Result {
        // Starting the player service from the background is refused on Android 12+. So the request
        // is kept, the watch opens the app on the phone (RemoteActivityHelper, simpmusic://wear), and
        // the request is played as soon as the activity has brought the player service up.
        if (!isPlayerServiceRunning()) {
            holdUntilPlayerRuns(body)
            return WearResponse.Result.Played(ok = false, needsPhoneApp = true)
        }
        return startPlaying(body)
    }

    private var pendingPlayJob: Job? = null

    private fun holdUntilPlayerRuns(body: WearRequest.Body.Play) {
        pendingPlayJob?.cancel()
        pendingPlayJob =
            scope.launch {
                val deadline = System.currentTimeMillis() + PENDING_PLAY_TIMEOUT_MS
                while (!isPlayerServiceRunning()) {
                    if (System.currentTimeMillis() > deadline) {
                        Logger.i(TAG, "Watch request dropped: the phone app was not opened")
                        return@launch
                    }
                    delay(250)
                }
                // The app restores the last queue as it starts; let that land first, or it would
                // replace the queue picked on the watch.
                delay(PENDING_PLAY_SETTLE_MS)
                try {
                    startPlaying(body)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Playing the watch's pick failed: ${e.message}")
                }
            }
    }

    private suspend fun startPlaying(body: WearRequest.Body.Play): WearResponse.Result {
        val (title, songs) = collection(body.collectionId)
        if (songs.isEmpty()) return WearResponse.Result.Played(ok = false, message = "empty")
        val ordered = if (body.shuffle) songs.shuffled() else songs
        val index = if (body.shuffle) 0 else body.index.coerceIn(0, ordered.lastIndex)
        val tracks = ordered.map { it.toTrack() }
        val isLocal = body.collectionId.startsWith(WearCollections.LOCAL_PREFIX)
        withContext(Dispatchers.Main) {
            handler.reset()
            handler.setQueueData(
                QueueData.Data(
                    listTracks = ArrayList(tracks),
                    firstPlayedTrack = tracks[index],
                    playlistId =
                        if (isLocal) LOCAL_PLAYLIST_ID + body.collectionId.removePrefix(WearCollections.LOCAL_PREFIX) else null,
                    playlistName = title,
                    playlistType = if (isLocal) PlaylistType.LOCAL_PLAYLIST else PlaylistType.RADIO,
                    continuation = null,
                ),
            )
            handler.loadMediaItem(tracks[index], Config.PLAYLIST_CLICK, index)
        }
        Logger.i(TAG, "Watch started $title at $index (${tracks.size} tracks)")
        requestPublish()
        return WearResponse.Result.Played(ok = true)
    }

    // ---- Helpers ----

    private fun adjustVolume(direction: Int) {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    private fun setVolume(level: Float) {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return
        val index = Math.round(level.coerceIn(0f, 1f) * max)
        if (index != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }
    }

    private fun musicVolume(): Float {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0f
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return 0f
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    /**
     * getRunningServices still lists the caller's own services on every Android version. Matched by
     * name because SimpleMediaService is internal to the media3 module.
     */
    @Suppress("DEPRECATION")
    private fun isPlayerServiceRunning(): Boolean {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == PLAYER_SERVICE_CLASS }
    }

    private const val PLAYER_SERVICE_CLASS = "com.maxrave.media3.service.SimpleMediaService"

    private fun SongEntity.toWearTrack() =
        WearTrack(
            videoId = videoId,
            title = title,
            artist = artistName?.joinToString(", ").orEmpty(),
            thumbnail = thumbnails,
        )

    private fun Track.toWearTrack() =
        WearTrack(
            videoId = videoId,
            title = title,
            artist = artists.toListName().joinToString(", "),
            thumbnail = thumbnails?.lastOrNull()?.url,
        )
}
