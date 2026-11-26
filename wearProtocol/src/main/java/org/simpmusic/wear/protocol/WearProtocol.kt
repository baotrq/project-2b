package org.simpmusic.wear.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Paths on the Wear Data Layer. Every one starts with [PREFIX], which is what the phone's
 * WearableListenerService filters on, so a path outside it never wakes the phone app.
 */
object WearPaths {
    const val PREFIX = "/simpmusic"

    /** DataItem the phone keeps current: [WearNowPlaying] under [KEY_STATE], cover under [KEY_ARTWORK]. */
    const val NOW_PLAYING = "/simpmusic/now_playing"

    /** Watch → phone, fire and forget: a [WearCommand]. */
    const val COMMAND = "/simpmusic/command"

    /** Watch → phone: a [WearRequest]. The phone answers on [RESPONSE] to the node that asked. */
    const val REQUEST = "/simpmusic/request"

    /** Phone → watch: a [WearResponse]. */
    const val RESPONSE = "/simpmusic/response"

    const val KEY_STATE = "state"
    const val KEY_ARTWORK = "artwork"

    /** Declared by the phone app (wearBridge res/values/wear.xml), so the watch finds the phone that has it. */
    const val PHONE_CAPABILITY = "simpmusic_phone"

    /** Declared by the watch app, so the phone publishes only while a watch with it is reachable. */
    const val WATCH_CAPABILITY = "simpmusic_watch"
}

/** Keeps message payloads well under the Data Layer's 100 KB cap. */
const val WEAR_MAX_LIST_SONGS = 200
const val WEAR_MAX_UP_NEXT = 30

val WearJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

@Serializable
data class WearTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnail: String? = null,
)

/**
 * What the phone is playing. Published on every change of track, play state, like, shuffle,
 * repeat, seek or queue, never per position tick: the watch carries the position forward itself
 * from [positionMs] taken at [positionAtEpochMs].
 */
@Serializable
data class WearNowPlaying(
    /** False when the phone's player service is not running: nothing can be controlled. */
    val sessionActive: Boolean = false,
    val track: WearTrack? = null,
    val isPlaying: Boolean = false,
    val isLiked: Boolean = false,
    val isShuffle: Boolean = false,
    /** 0 off, 1 one, 2 all. */
    val repeatMode: Int = 0,
    val isLive: Boolean = false,
    /**
     * Two songs are fading into each other. The phone switches [track] to the incoming song early in
     * the fade, so the watch keeps showing the outgoing cover until this turns false again.
     */
    val isCrossfading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val positionAtEpochMs: Long = 0,
    /** The phone's music volume, 0..1. */
    val volume: Float = 0f,
    /** Index of the playing track in the phone's queue; [upNext] are the tracks after it, in order. */
    val queueIndex: Int = -1,
    val upNext: List<WearTrack> = emptyList(),
    val queueName: String? = null,
    /** Changes with every publish, so the Data Layer never drops an update as "unchanged". */
    val updatedAt: Long = 0,
)

@Serializable
sealed interface WearCommand {
    @Serializable
    @SerialName("play_pause")
    data object PlayPause : WearCommand

    @Serializable
    @SerialName("next")
    data object Next : WearCommand

    @Serializable
    @SerialName("previous")
    data object Previous : WearCommand

    @Serializable
    @SerialName("seek")
    data class SeekTo(
        val positionMs: Long,
    ) : WearCommand

    @Serializable
    @SerialName("like")
    data object ToggleLike : WearCommand

    @Serializable
    @SerialName("shuffle")
    data object Shuffle : WearCommand

    @Serializable
    @SerialName("repeat")
    data object Repeat : WearCommand

    @Serializable
    @SerialName("volume_up")
    data object VolumeUp : WearCommand

    @Serializable
    @SerialName("volume_down")
    data object VolumeDown : WearCommand

    /** Set the phone's music volume to [level] (0..1), rounded to the nearest step the phone has. */
    @Serializable
    @SerialName("set_volume")
    data class SetVolume(
        val level: Float,
    ) : WearCommand

    /** Jump to [queueIndex] in the phone's current queue, counted as [WearNowPlaying.queueIndex] is. */
    @Serializable
    @SerialName("play_queue_item")
    data class PlayQueueItem(
        val queueIndex: Int,
    ) : WearCommand
}

/** Ids of the fixed library collections; a local playlist is `local:<id>`. */
object WearCollections {
    const val FAVORITES = "favorites"
    const val DOWNLOADED = "downloaded"
    const val MOST_PLAYED = "most_played"
    const val RECENT = "recent"
    const val LOCAL_PREFIX = "local:"
}

@Serializable
data class WearCollection(
    val id: String,
    val title: String,
    val count: Int,
    val thumbnail: String? = null,
)

@Serializable
data class WearRequest(
    val id: String,
    val body: Body,
) {
    @Serializable
    sealed interface Body {
        /** Is the phone app reachable, and is its player running? Also makes the phone republish now playing. */
        @Serializable
        @SerialName("ping")
        data object Ping : Body

        @Serializable
        @SerialName("library")
        data object Library : Body

        @Serializable
        @SerialName("collection")
        data class Collection(
            val collectionId: String,
        ) : Body

        /** Play [collectionId] from [index], or shuffled when [shuffle] (then [index] is ignored). */
        @Serializable
        @SerialName("play")
        data class Play(
            val collectionId: String,
            val index: Int = 0,
            val shuffle: Boolean = false,
        ) : Body
    }
}

@Serializable
data class WearResponse(
    val id: String,
    val result: Result,
) {
    @Serializable
    sealed interface Result {
        @Serializable
        @SerialName("pong")
        data class Pong(
            val serviceRunning: Boolean,
        ) : Result

        @Serializable
        @SerialName("library")
        data class Library(
            val collections: List<WearCollection>,
        ) : Result

        @Serializable
        @SerialName("songs")
        data class Songs(
            val title: String,
            val songs: List<WearTrack>,
        ) : Result

        /** [ok] false with [needsPhoneApp]: the phone's player is not running, so the app must be opened there. */
        @Serializable
        @SerialName("played")
        data class Played(
            val ok: Boolean,
            val needsPhoneApp: Boolean = false,
            val message: String? = null,
        ) : Result

        @Serializable
        @SerialName("error")
        data class Error(
            val message: String,
        ) : Result
    }
}

fun WearNowPlaying.encode(): ByteArray = WearJson.encodeToString(WearNowPlaying.serializer(), this).encodeToByteArray()

fun decodeNowPlaying(bytes: ByteArray): WearNowPlaying? =
    runCatching { WearJson.decodeFromString(WearNowPlaying.serializer(), bytes.decodeToString()) }.getOrNull()

fun WearCommand.encode(): ByteArray = WearJson.encodeToString(WearCommand.serializer(), this).encodeToByteArray()

fun decodeCommand(bytes: ByteArray): WearCommand? =
    runCatching { WearJson.decodeFromString(WearCommand.serializer(), bytes.decodeToString()) }.getOrNull()

fun WearRequest.encode(): ByteArray = WearJson.encodeToString(WearRequest.serializer(), this).encodeToByteArray()

fun decodeRequest(bytes: ByteArray): WearRequest? =
    runCatching { WearJson.decodeFromString(WearRequest.serializer(), bytes.decodeToString()) }.getOrNull()

fun WearResponse.encode(): ByteArray = WearJson.encodeToString(WearResponse.serializer(), this).encodeToByteArray()

fun decodeResponse(bytes: ByteArray): WearResponse? =
    runCatching { WearJson.decodeFromString(WearResponse.serializer(), bytes.decodeToString()) }.getOrNull()
