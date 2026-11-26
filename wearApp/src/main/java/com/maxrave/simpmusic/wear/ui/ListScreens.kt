package com.maxrave.simpmusic.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.maxrave.simpmusic.wear.PhoneLink
import com.maxrave.simpmusic.wear.R
import com.maxrave.simpmusic.wear.icon.MusicNote
import com.maxrave.simpmusic.wear.icon.Shuffle
import com.maxrave.simpmusic.wear.icon.SimpIcons
import com.maxrave.simpmusic.wear.icon.Smartphone
import kotlinx.coroutines.launch
import org.simpmusic.wear.protocol.WearCollection
import org.simpmusic.wear.protocol.WearCommand
import org.simpmusic.wear.protocol.WearRequest
import org.simpmusic.wear.protocol.WearResponse
import org.simpmusic.wear.protocol.WearTrack

/** YouTube serves its covers at any size; a list row needs about 96 px. */
private val SIZE_SEGMENT = Regex("=w\\d+-h\\d+")

private fun smallThumbnail(url: String?): String? = url?.let { SIZE_SEGMENT.replace(it, "=w96-h96") }

@Composable
fun QueueScreen(onPlayed: () -> Unit) {
    val state by PhoneLink.nowPlaying.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val current = state
    val upNext = current?.upNext.orEmpty()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(current?.queueName ?: stringResource(R.string.queue)) } }
            if (upNext.isEmpty()) {
                item { EmptyText(stringResource(R.string.queue_empty)) }
            }
            itemsIndexed(upNext) { offset, track ->
                TrackButton(track) {
                    // upNext starts right after the playing track.
                    PhoneLink.send(WearCommand.PlayQueueItem(current!!.queueIndex + 1 + offset))
                    onPlayed()
                }
            }
        }
    }
}

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>

    data object Failed : Loaded<Nothing>

    data class Ready<T>(
        val value: T,
    ) : Loaded<T>
}

@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf<Loaded<List<WearCollection>>>(Loaded.Loading) }
    LaunchedEffect(attempt) {
        loaded = Loaded.Loading
        val result = PhoneLink.request(WearRequest.Body.Library) as? WearResponse.Result.Library
        loaded = if (result == null) Loaded.Failed else Loaded.Ready(result.collections)
    }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(stringResource(R.string.library)) } }
            when (val value = loaded) {
                Loaded.Loading -> item { EmptyText(stringResource(R.string.connecting)) }
                Loaded.Failed -> retryItems { attempt++ }
                is Loaded.Ready -> {
                    if (value.value.isEmpty()) item { EmptyText(stringResource(R.string.empty_list)) }
                    itemsIndexed(value.value) { _, collection ->
                        FilledTonalButton(
                            onClick = { onOpen(collection.id) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(collection.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = { Text(stringResource(R.string.songs_count, collection.count)) },
                            icon = { Thumbnail(collection.thumbnail) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CollectionScreen(
    collectionId: String,
    onPlayed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf<Loaded<WearResponse.Result.Songs>>(Loaded.Loading) }
    var needsPhoneApp by remember { mutableStateOf(false) }
    LaunchedEffect(collectionId, attempt) {
        loaded = Loaded.Loading
        val result = PhoneLink.request(WearRequest.Body.Collection(collectionId)) as? WearResponse.Result.Songs
        loaded = if (result == null) Loaded.Failed else Loaded.Ready(result)
    }

    fun play(
        index: Int,
        shuffle: Boolean,
    ) {
        scope.launch {
            val result = PhoneLink.request(WearRequest.Body.Play(collectionId, index, shuffle)) as? WearResponse.Result.Played
            when {
                result?.ok == true -> onPlayed()
                // The phone kept the pick and plays it once its app is up: open the app there and
                // go back to the player, which follows the phone as soon as it starts.
                result?.needsPhoneApp == true -> {
                    PhoneLink.openOnPhone()
                    onPlayed()
                }
                // No answer at all (phone asleep, link down): offer to open it by hand.
                result == null -> needsPhoneApp = true
            }
        }
    }

    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            val value = loaded
            item {
                ListHeader {
                    Text(
                        (value as? Loaded.Ready)?.value?.title ?: stringResource(R.string.library),
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (needsPhoneApp) {
                item { EmptyText(stringResource(R.string.player_not_running)) }
                item {
                    Button(
                        onClick = {
                            PhoneLink.openOnPhone()
                            needsPhoneApp = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.open_on_phone)) },
                        icon = { Icon(SimpIcons.Smartphone, null) },
                    )
                }
            }
            when (value) {
                Loaded.Loading -> item { EmptyText(stringResource(R.string.connecting)) }
                Loaded.Failed -> retryItems { attempt++ }
                is Loaded.Ready -> {
                    val songs = value.value.songs
                    if (songs.isEmpty()) {
                        item { EmptyText(stringResource(R.string.empty_list)) }
                    } else {
                        item {
                            Button(
                                onClick = { play(0, shuffle = true) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.shuffle_all)) },
                                icon = { Icon(SimpIcons.Shuffle, null) },
                            )
                        }
                    }
                    itemsIndexed(songs) { index, track -> TrackButton(track) { play(index, shuffle = false) } }
                }
            }
        }
    }
}

@Composable
private fun TrackButton(
    track: WearTrack,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Thumbnail(track.thumbnail) },
    )
}

@Composable
private fun Thumbnail(url: String?) {
    val placeholder = rememberVectorPainter(SimpIcons.MusicNote)
    AsyncImage(
        model = smallThumbnail(url),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        modifier = Modifier.size(ButtonDefaults.LargeIconSize).clip(RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun ScalingLazyListScope.retryItems(onRetry: () -> Unit) {
    item { EmptyText(stringResource(R.string.load_failed)) }
    item {
        FilledTonalButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.retry)) },
        )
    }
}
