package com.maxrave.simpmusic.wear.ui

import androidx.compose.animation.AnimatedVisibility
import kotlin.math.ceil
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.LinearOutSlowInEasing
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.maxrave.simpmusic.wear.PhoneLink
import com.maxrave.simpmusic.wear.PhoneState
import com.maxrave.simpmusic.wear.R
import com.maxrave.simpmusic.wear.icon.Favorite
import com.maxrave.simpmusic.wear.icon.FavoriteBorder
import com.maxrave.simpmusic.wear.icon.LibraryMusic
import com.maxrave.simpmusic.wear.icon.Pause
import com.maxrave.simpmusic.wear.icon.PlayArrow
import com.maxrave.simpmusic.wear.icon.QueueMusic
import com.maxrave.simpmusic.wear.icon.SimpIcons
import com.maxrave.simpmusic.wear.icon.SkipNext
import com.maxrave.simpmusic.wear.icon.SkipPrevious
import com.maxrave.simpmusic.wear.positionAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import org.simpmusic.wear.protocol.WearCommand
import org.simpmusic.wear.protocol.WearNowPlaying

/** Crown travel (in rotary pixels) from silent to full volume. */
private const val ROTARY_PX_FULL_RANGE = 720f

/** How often the level is sent to the phone while the crown turns. */
private const val VOLUME_SEND_INTERVAL_MS = 100L

/** Controls fade out after this long untouched, leaving the cover, the ring and the title. */
private const val CONTROLS_HIDE_MS = 5_000L

/** How long the volume arc stays up after the crown stops. */
private const val VOLUME_SHOWN_MS = 1_500L

@Composable
fun PlayerScreen(
    onOpenQueue: () -> Unit,
    onOpenLibrary: () -> Unit,
    autoOpenLibrary: Boolean,
) {
    val phone by PhoneLink.phone.collectAsStateWithLifecycle()
    val state by PhoneLink.nowPlaying.collectAsStateWithLifecycle()
    val artwork by PhoneLink.artwork.collectAsStateWithLifecycle()

    // Controls fade out after CONTROLS_HIDE_MS untouched, leaving the bright cover, the ring and the
    // title; a tap anywhere brings them back. Every touch restarts the countdown.
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouchAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controlsVisible, lastTouchAt) {
        if (controlsVisible) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }
    val scrimAlpha by animateFloatAsState(if (controlsVisible) 1f else 0f, label = "scrim")

    ScreenScaffold { _ ->
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Observe only: the touch still reaches the button under it.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        lastTouchAt = System.currentTimeMillis()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val live = state
            val active = live?.sessionActive == true && live.track != null
            val crossfading = active && live!!.isCrossfading

            // The phone moves to the incoming song early in a crossfade. Hold the outgoing song's
            // cover and title until the fade has finished, then let the new ones in.
            var heldState by remember { mutableStateOf(live) }
            var heldArtwork by remember { mutableStateOf(artwork) }
            LaunchedEffect(live, artwork, crossfading) {
                if (!crossfading) {
                    heldState = live
                    heldArtwork = artwork
                }
            }
            val current =
                if (crossfading) heldState?.copy(isPlaying = live!!.isPlaying, isCrossfading = true) ?: live else live
            val shownArtwork = if (crossfading) heldArtwork else artwork

            if (active && shownArtwork != null) {
                VinylCover(latest = artwork ?: shownArtwork, crossfading = crossfading, modifier = Modifier.fillMaxSize())
                // Hidden controls: the cover stays sharp in the middle and softens toward the rim.
                if (!crossfading && scrimAlpha < 1f) {
                    BlurredRim(cover = shownArtwork, modifier = Modifier.fillMaxSize().alpha(1f - scrimAlpha))
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .alpha(scrimAlpha)
                        .background(
                            Brush.radialGradient(
                                0f to Color.Black.copy(alpha = 0.55f),
                                1f to Color.Black.copy(alpha = 0.85f),
                            ),
                        ),
                )
            }
            when {
                active ->
                    NowPlaying(
                        state = current!!,
                        controlsVisible = controlsVisible || shownArtwork == null,
                        crossfading = crossfading,
                        onShowControls = { controlsVisible = true },
                        onHideControls = { controlsVisible = false },
                        onOpenQueue = onOpenQueue,
                        onOpenLibrary = onOpenLibrary,
                    )
                phone is PhoneState.NoPhone ->
                    Message(
                        title = stringResource(R.string.no_phone),
                        detail = stringResource(R.string.no_phone_detail),
                        action = stringResource(R.string.retry),
                        onAction = PhoneLink::refresh,
                    )
                phone is PhoneState.Connected && current != null && !current.sessionActive -> {
                    OpenLibraryOnce(autoOpenLibrary, onOpenLibrary)
                    Message(
                        title = stringResource(R.string.player_not_running),
                        detail = null,
                        action = stringResource(R.string.library),
                        onAction = onOpenLibrary,
                        secondary = stringResource(R.string.open_on_phone) to { PhoneLink.openOnPhone() },
                    )
                }
                phone is PhoneState.Connected -> {
                    OpenLibraryOnce(autoOpenLibrary, onOpenLibrary)
                    Message(
                        title = stringResource(R.string.nothing_playing),
                        detail = null,
                        action = stringResource(R.string.library),
                        onAction = onOpenLibrary,
                    )
                }
                else ->
                    Message(
                        title = stringResource(R.string.connecting),
                        detail = null,
                        action = null,
                        onAction = {},
                    )
            }
        }
    }
}

@Composable
private fun NowPlaying(
    state: WearNowPlaying,
    controlsVisible: Boolean,
    crossfading: Boolean,
    onShowControls: () -> Unit,
    onHideControls: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val track = state.track ?: return

    val currentState by rememberUpdatedState(state)

    // The phone reports position only when something changes; carry it forward once a second.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isPlaying, state.positionAtEpochMs) {
        now = System.currentTimeMillis()
        while (state.isPlaying) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    // Crown → phone volume. Turning starts from the phone's level, then the watch owns the arc: it
    // moves smoothly with the crown and sends the absolute level to the phone, ignoring the phone's
    // echoes (which arrive late and rounded to its steps, and made the arc jump back). Once the arc
    // has gone, the phone's level is taken again — including changes made on the phone itself.
    val focus = remember { FocusRequester() }
    var shownVolume by remember { mutableFloatStateOf(state.volume) }
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    var volumeVisible by remember { mutableStateOf(false) }
    var volumeTicks by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.volume, state.updatedAt, volumeVisible) {
        if (!volumeVisible) shownVolume = state.volume
    }
    LaunchedEffect(volumeTicks) {
        if (volumeTicks == 0) return@LaunchedEffect
        volumeVisible = true
        delay(VOLUME_SHOWN_MS)
        if (System.currentTimeMillis() - volumeShownAt >= VOLUME_SHOWN_MS) volumeVisible = false
    }
    // At most ~10 sends a second while turning; sample() still delivers the last level.
    LaunchedEffect(Unit) {
        snapshotFlow { if (volumeVisible) shownVolume else null }
            .filterNotNull()
            .distinctUntilChanged()
            .sample(VOLUME_SEND_INTERVAL_MS)
            .collect { PhoneLink.send(WearCommand.SetVolume(it)) }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                shownVolume = (shownVolume + event.verticalScrollPixels / ROTARY_PX_FULL_RANGE).coerceIn(0f, 1f)
                volumeShownAt = System.currentTimeMillis()
                volumeTicks++
                true
            }.focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        if (state.durationMs > 0 && !volumeVisible && !crossfading) {
            CircularProgressIndicator(
                // The indicator keeps the FIRST lambda it was given (snapshotFlow inside a
                // LaunchedEffect(Unit)) and only re-runs it when snapshot state it reads changes. A
                // lambda that closed over the `state` parameter therefore kept the first song's
                // position and length after every track change. Read both through State instead.
                progress = {
                    val current = currentState
                    if (current.durationMs <= 0) 0f else (positionAt(current, now).toFloat() / current.durationMs).coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxSize().padding(2.dp),
                strokeWidth = 4.dp,
            )
        }
        // LevelIndicator draws its arc inside the box it is given, so that box must be the whole
        // screen, with the indicator against its start edge (where the crown's own arc would sit).
        AnimatedVisibility(
            visible = volumeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                LevelIndicator(value = { shownVolume }, modifier = Modifier.align(Alignment.CenterStart))
            }
        }

        // Hidden controls: the cover at full brightness, a soft shadow at the bottom for the title,
        // and the whole screen one tap target that brings the controls back (and presses nothing).
        AnimatedVisibility(
            visible = !controlsVisible && !crossfading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onShowControls,
                    ),
            ) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.4f)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))),
                )
                // The title runs clockwise along the bottom edge like a train: out of a tunnel near
                // 4 o'clock, past 6, into a tunnel near 8, then round again, so a long title can be
                // read in full.
                TitleTrain(title = track.title, modifier = Modifier.fillMaxSize())
            }
        }

        // A tap on the screen outside the buttons hides the controls. This layer sits under them, so
        // the buttons take their own taps first and only the empty space reaches it.
        if (controlsVisible && !crossfading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onHideControls,
                    ),
            )
        }

        AnimatedVisibility(visible = controlsVisible && !crossfading, enter = fadeIn(), exit = fadeOut()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(18.dp))
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(
                text = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { PhoneLink.send(WearCommand.Previous) }) {
                    Icon(SimpIcons.SkipPrevious, stringResource(R.string.previous))
                }
                FilledIconButton(
                    onClick = { PhoneLink.send(WearCommand.PlayPause) },
                    modifier = Modifier.size(IconButtonDefaults.LargeButtonSize),
                ) {
                    Icon(
                        if (state.isPlaying) SimpIcons.Pause else SimpIcons.PlayArrow,
                        stringResource(R.string.play_pause),
                        modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
                    )
                }
                IconButton(onClick = { PhoneLink.send(WearCommand.Next) }) {
                    Icon(SimpIcons.SkipNext, stringResource(R.string.next))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(
                    onClick = { PhoneLink.send(WearCommand.ToggleLike) },
                    modifier = Modifier.size(IconButtonDefaults.SmallButtonSize),
                ) {
                    Icon(
                        if (state.isLiked) SimpIcons.Favorite else SimpIcons.FavoriteBorder,
                        stringResource(R.string.like),
                        tint = if (state.isLiked) Color(0xFFE53935) else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onOpenQueue, modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)) {
                    Icon(SimpIcons.QueueMusic, stringResource(R.string.queue))
                }
                IconButton(onClick = onOpenLibrary, modifier = Modifier.size(IconButtonDefaults.SmallButtonSize)) {
                    Icon(SimpIcons.LibraryMusic, stringResource(R.string.library))
                }
            }
        }
        }
    }
}

/**
 * A blurred copy of the cover, masked so it shows only near the edge of the round screen and fades
 * out toward the centre. Blur needs Android 12; older watches would get the unblurred copy, which
 * looks the same as no effect.
 */
@Composable
private fun BlurredRim(
    cover: ImageBitmap,
    modifier: Modifier,
) {
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush =
                        Brush.radialGradient(
                            RIM_BLUR_CLEAR to Color.Transparent,
                            RIM_BLUR_FULL to Color.Black,
                            radius = size.minDimension / 2f,
                        ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Image(
            bitmap = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(RIM_BLUR_RADIUS),
        )
    }
}

/** Fractions of the screen radius: sharp inside [RIM_BLUR_CLEAR], fully blurred from [RIM_BLUR_FULL] out. */
private const val RIM_BLUR_CLEAR = 0.66f
private const val RIM_BLUR_FULL = 0.94f
private val RIM_BLUR_RADIUS = 6.dp

/**
 * The cover, and the records that turn around a song change.
 *
 * Crossfade: the current cover splits into two big spinning records centred on the left and right
 * edges, so only their inner halves show and meet near the middle: the outgoing song's on
 * the left and the incoming song's on the right (blank until the phone sends its cover). When the
 * phone has fully switched, the left record rolls off, and the right one moves to the centre, spins
 * down to upright and grows back into the full cover. A change without a crossfade (skip, or
 * crossfade off) is a single roll: the old cover leaves left as the new one rolls in from the right.
 */
@Composable
private fun VinylCover(
    latest: ImageBitmap,
    crossfading: Boolean,
    modifier: Modifier,
) {
    // The cover at rest; stays the outgoing song's through a crossfade.
    var resting by remember { mutableStateOf(latest) }
    var leaving by remember { mutableStateOf<ImageBitmap?>(null) }
    val split = remember { Animatable(0f) } // 0 one cover, 1 two records side by side
    val merge = remember { Animatable(0f) } // 0 two records, 1 the right one is the cover again
    val roll = remember { Animatable(1f) } // single roll: 0 new cover off to the right, 1 at rest
    var spin by remember { mutableFloatStateOf(0f) } // degrees, grows while records turn
    val settle = remember { Animatable(0f) } // rotation of the record that becomes the cover

    val latestState by rememberUpdatedState(latest)
    val turning = crossfading || split.value > 0f || leaving != null
    LaunchedEffect(turning) {
        if (!turning) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            spin += (now - last) / 1_000_000_000f * VINYL_DEG_PER_SECOND
            last = now
        }
    }
    LaunchedEffect(crossfading) {
        if (crossfading) {
            split.animateTo(1f, tween(VINYL_SPLIT_MS, easing = FastOutSlowInEasing))
        } else if (split.value > 0f) {
            // The switch is done: the right record becomes the cover, spinning down to upright.
            val from = spin
            settle.snapTo(from)
            launch { settle.animateTo(ceil(from / 360f) * 360f + 360f, tween(VINYL_MERGE_MS, easing = LinearOutSlowInEasing)) }
            merge.animateTo(1f, tween(VINYL_MERGE_MS, easing = FastOutSlowInEasing))
            resting = latestState
            split.snapTo(0f)
            merge.snapTo(0f)
        }
    }
    LaunchedEffect(latest, crossfading) {
        if (crossfading || split.value > 0f || latest == resting) return@LaunchedEffect
        leaving = resting
        resting = latest
        roll.snapTo(0f)
        roll.animateTo(1f, tween(VINYL_ROLL_MS, easing = LinearOutSlowInEasing))
        leaving = null
    }

    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val outgoing = leaving
        when {
            split.value > 0f -> {
                val s = split.value
                val m = merge.value
                val incoming = latest.takeIf { it != resting }
                // Left: the outgoing song, then rolling off to the left.
                Record(
                    cover = resting,
                    recordness = 1f,
                    rotation = spin,
                    scale = lerp(1f, VINYL_PAIR_SCALE, s),
                    offsetX = (-VINYL_PAIR_OFFSET * s - (1f - VINYL_PAIR_OFFSET) * m) * width,
                    alpha = 1f - m,
                )
                // Right: the incoming song, then to the centre and back to a full cover.
                Record(
                    cover = incoming,
                    recordness = 1f - m,
                    rotation = if (m > 0f) settle.value else spin,
                    scale = lerp(lerp(1f, VINYL_PAIR_SCALE, s), 1f, m),
                    offsetX = VINYL_PAIR_OFFSET * s * (1f - m) * width,
                    alpha = s,
                )
            }
            outgoing != null -> {
                val r = roll.value
                Record(outgoing, recordness = r, rotation = spin, scale = 1f - (1f - VINYL_PAIR_SCALE) * r, offsetX = -r * width, alpha = 1f)
                Record(
                    resting,
                    recordness = 1f - r,
                    rotation = -(1f - r) * VINYL_ROLL_DEGREES,
                    scale = lerp(VINYL_PAIR_SCALE, 1f, r),
                    offsetX = (1f - r) * width,
                    alpha = 1f,
                )
            }
            else -> Record(resting, recordness = 0f, rotation = 0f, scale = 1f, offsetX = 0f, alpha = 1f)
        }
    }
}

/** A cover drawn as a record: [recordness] 0 is the plain cover, 1 a disc with grooves and a hole. */
@Composable
private fun Record(
    cover: ImageBitmap?,
    recordness: Float,
    rotation: Float,
    scale: Float,
    offsetX: Float,
    alpha: Float,
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                rotationZ = rotation
                this.alpha = alpha
                clip = true
                shape = CircleShape
            }.background(Color(0xFF111111)),
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (recordness > 0f) {
            Canvas(Modifier.fillMaxSize().alpha(recordness)) {
                val r = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f), radius = r),
                    radius = r,
                )
                for (i in 1..VINYL_GROOVES) {
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.22f),
                        radius = r * (0.42f + 0.55f * i / VINYL_GROOVES),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                // A highlight off-centre, so the turning is visible even on a plain cover.
                drawArc(
                    color = Color.White.copy(alpha = 0.18f),
                    startAngle = -60f,
                    sweepAngle = 40f,
                    useCenter = false,
                    topLeft = Offset(r * 0.25f, r * 0.25f),
                    size = Size(r * 1.5f, r * 1.5f),
                    style = Stroke(width = 3.dp.toPx()),
                )
                drawCircle(color = Color.Black.copy(alpha = 0.85f), radius = r * 0.08f)
                drawCircle(color = Color.White.copy(alpha = 0.25f), radius = r * 0.08f, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

private fun lerp(
    a: Float,
    b: Float,
    t: Float,
): Float = a + (b - a) * t

private const val VINYL_DEG_PER_SECOND = 70f
private const val VINYL_PAIR_SCALE = 0.92f
private const val VINYL_PAIR_OFFSET = 0.5f
private const val VINYL_SPLIT_MS = 800
private const val VINYL_MERGE_MS = 1_000
private const val VINYL_ROLL_MS = 1_000
private const val VINYL_ROLL_DEGREES = 300f
private const val VINYL_GROOVES = 5

/**
 * The song title travelling clockwise along the bottom edge of the round screen: it comes out of a
 * tunnel near 4 o'clock, runs right-to-left past 6 o'clock (upright and readable) and disappears into
 * a tunnel near 8 o'clock; the moment its tail is gone, its head comes out of the first tunnel again.
 *
 * The path it runs on goes almost all the way round, far beyond both tunnels: drawTextOnPath piles
 * up the glyphs that fall off either end of a path, and that pile must be where the mask hides it,
 * not at a tunnel mouth.
 */
@Composable
private fun TitleTrain(
    title: String,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val paint =
        remember(density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textSize = with(density) { 16.sp.toPx() }
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setShadowLayer(with(density) { 3.dp.toPx() }, 0f, 0f, android.graphics.Color.BLACK)
            }
        }
    BoxWithConstraints(modifier) {
        val size = constraints.maxWidth.coerceAtMost(constraints.maxHeight).toFloat()
        val radius = size / 2f - with(density) { TRAIN_INSET.toPx() }
        val degToPx = radius * (PI.toFloat() / 180f)
        // The path starts at 6 o'clock + TRAIN_PATH_HALF degrees and runs counter-clockwise, i.e.
        // left to right along the bottom, so the letters stand upright.
        val pathStartAngle = 90f + TRAIN_PATH_HALF
        val path =
            remember(size, radius) {
                android.graphics.Path().apply {
                    val c = size / 2f
                    addArc(c - radius, c - radius, c + radius, c + radius, pathStartAngle, -2f * TRAIN_PATH_HALF)
                }
            }
        // The visible stretch, as distances along the path.
        val windowStart = (TRAIN_PATH_HALF - TRAIN_HALF_SWEEP) * degToPx
        val windowEnd = (TRAIN_PATH_HALF + TRAIN_HALF_SWEEP) * degToPx
        val textWidth = remember(title, paint) { paint.measureText(title) }
        // One lap: head enters at the right tunnel, tail leaves at the left one, next lap starts.
        val lap = (windowEnd - windowStart) + textWidth
        val travelled = remember(title) { Animatable(0f) }
        LaunchedEffect(title, lap) {
            val pxPerSecond = TRAIN_DEG_PER_SECOND * degToPx
            val lapMs = (lap / pxPerSecond * 1000).toInt()
            while (isActive) {
                travelled.snapTo(0f)
                travelled.animateTo(lap, tween(lapMs, easing = LinearEasing))
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Tunnels: the text fades out toward both ends of the visible stretch, and
                    // everything outside it (including the piled-up glyphs) is cut away.
                    val start = (90f - TRAIN_HALF_SWEEP) / 360f
                    val end = (90f + TRAIN_HALF_SWEEP) / 360f
                    val fade = TRAIN_TUNNEL_DEGREES / 360f
                    drawRect(
                        brush =
                            Brush.sweepGradient(
                                0f to Color.Transparent,
                                start to Color.Transparent,
                                start + fade to Color.Black,
                                end - fade to Color.Black,
                                end to Color.Transparent,
                                1f to Color.Transparent,
                            ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawTextOnPath(title, path, windowEnd - travelled.value, 0f, paint)
                }
            }
        }
    }
}

/** Half the bottom arc the title is visible on, either side of 6 o'clock (60 = from 4 to 8 o'clock). */
private const val TRAIN_HALF_SWEEP = 60f

/** Half the path the title runs on; well past the visible arc, so its ends are always hidden. */
private const val TRAIN_PATH_HALF = 170f
private const val TRAIN_TUNNEL_DEGREES = 18f
private const val TRAIN_DEG_PER_SECOND = 25f
private val TRAIN_INSET = 12.dp

/**
 * With nothing to control on the phone, the watch is there to pick something: go to the Library,
 * once per launch. The wait lets the phone answer the launch ping first, so a state cached from an
 * earlier session cannot send the user away from a phone that is in fact playing (leaving this
 * branch cancels the wait).
 */
@Composable
private fun OpenLibraryOnce(
    enabled: Boolean,
    onOpenLibrary: () -> Unit,
) {
    if (!enabled) return
    LaunchedEffect(Unit) {
        delay(AUTO_LIBRARY_DELAY_MS)
        onOpenLibrary()
    }
}

private const val AUTO_LIBRARY_DELAY_MS = 1_200L

@Composable
private fun Message(
    title: String,
    detail: String?,
    action: String?,
    onAction: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
        if (secondary != null) {
            Spacer(Modifier.height(4.dp))
            FilledTonalButton(onClick = secondary.second) { Text(secondary.first) }
        }
    }
}
