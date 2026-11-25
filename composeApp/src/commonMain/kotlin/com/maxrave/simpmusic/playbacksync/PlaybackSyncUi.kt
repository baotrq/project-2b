package com.maxrave.simpmusic.playbacksync

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.simpmusic.Platform
import com.maxrave.simpmusic.expect.copyToClipboard
import com.maxrave.simpmusic.expect.ui.PlaybackSyncScanSheet
import com.maxrave.simpmusic.expect.ui.rememberLocalNetworkPermission
import com.maxrave.simpmusic.extension.formatDuration
import com.maxrave.simpmusic.getPlatform
import com.maxrave.simpmusic.ui.component.SettingItem
import com.maxrave.simpmusic.ui.component.QrCode as QrCodeImage
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.CloudSync
import com.maxrave.simpmusic.ui.icon.QrCode
import com.maxrave.simpmusic.ui.icon.Devices
import com.maxrave.simpmusic.ui.icon.Key
import com.maxrave.simpmusic.ui.icon.Link
import com.maxrave.simpmusic.ui.icon.PlayArrow
import com.maxrave.simpmusic.ui.icon.Refresh
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.continue_from_device
import simpmusic.composeapp.generated.resources.continue_listening
import simpmusic.composeapp.generated.resources.dismiss
import simpmusic.composeapp.generated.resources.playback_sync_device_name
import simpmusic.composeapp.generated.resources.playback_sync_enable
import simpmusic.composeapp.generated.resources.playback_sync_enable_description
import simpmusic.composeapp.generated.resources.playback_sync_failed
import simpmusic.composeapp.generated.resources.playback_sync_incomplete
import simpmusic.composeapp.generated.resources.playback_sync_key
import simpmusic.composeapp.generated.resources.playback_sync_key_set
import simpmusic.composeapp.generated.resources.playback_sync_not_set
import simpmusic.composeapp.generated.resources.playback_sync_ok
import simpmusic.composeapp.generated.resources.playback_sync_server
import simpmusic.composeapp.generated.resources.playback_sync_test
import simpmusic.composeapp.generated.resources.playback_sync_test_hint
import simpmusic.composeapp.generated.resources.playback_sync_testing
import simpmusic.composeapp.generated.resources.playback_sync_unauthorized
import simpmusic.composeapp.generated.resources.save
import simpmusic.composeapp.generated.resources.ok
import simpmusic.composeapp.generated.resources.playback_sync_code_copied
import simpmusic.composeapp.generated.resources.playback_sync_code_how
import simpmusic.composeapp.generated.resources.playback_sync_code_title
import simpmusic.composeapp.generated.resources.playback_sync_code_warning
import simpmusic.composeapp.generated.resources.playback_sync_copy_code
import simpmusic.composeapp.generated.resources.playback_sync_join
import simpmusic.composeapp.generated.resources.playback_sync_receive_description
import simpmusic.composeapp.generated.resources.playback_sync_receive_how
import simpmusic.composeapp.generated.resources.playback_sync_receive_no_network
import simpmusic.composeapp.generated.resources.playback_sync_receive_title
import simpmusic.composeapp.generated.resources.playback_sync_scan_hint
import simpmusic.composeapp.generated.resources.playback_sync_send_failed
import simpmusic.composeapp.generated.resources.playback_sync_send_not_set_up
import simpmusic.composeapp.generated.resources.playback_sync_send_scan_hint
import simpmusic.composeapp.generated.resources.playback_sync_sending
import simpmusic.composeapp.generated.resources.playback_sync_sent
import simpmusic.composeapp.generated.resources.playback_sync_show_my_code
import simpmusic.composeapp.generated.resources.playback_sync_join_message
import simpmusic.composeapp.generated.resources.playback_sync_join_title
import simpmusic.composeapp.generated.resources.playback_sync_joined
import simpmusic.composeapp.generated.resources.playback_sync_paste_invalid
import simpmusic.composeapp.generated.resources.playback_sync_paste_title
import simpmusic.composeapp.generated.resources.playback_sync_scan_title
import simpmusic.composeapp.generated.resources.playback_sync_show_code
import simpmusic.composeapp.generated.resources.playback_sync_show_code_description

/** Rows of the "Playback sync" settings group. */
@Composable
fun ColumnScope.PlaybackSyncSettingItems() {
    val dataStore: DataStoreManager = koinInject()
    val scope = rememberCoroutineScope()
    val url by remember { dataStore.getString(PlaybackSync.KEY_URL) }.collectAsStateWithLifecycle(null)
    val secret by remember { dataStore.getString(PlaybackSync.KEY_SECRET) }.collectAsStateWithLifecycle(null)
    val name by remember { dataStore.getString(PlaybackSync.KEY_DEVICE_NAME) }.collectAsStateWithLifecycle(null)

    // The DataStore key being edited, or null while no dialog is open.
    var editing by remember { mutableStateOf<String?>(null) }
    var check by remember { mutableStateOf<PlaybackSync.ConnectionCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    val enabledFlag by remember { dataStore.getString(PlaybackSync.KEY_ENABLED) }.collectAsStateWithLifecycle(null)
    val notSet = stringResource(Res.string.playback_sync_not_set)
    // Unset counts as on once a server is saved, so an install that already syncs keeps syncing.
    val enabled = enabledFlag == DataStoreManager.TRUE || (enabledFlag == null && !url.isNullOrBlank())

    // Moving the setup between devices, either way round. The joining device can show a receive code
    // for the other to scan (it may have no working camera), or scan/paste the other's setup code.
    var showingCode by remember { mutableStateOf<String?>(null) }
    var receiving by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf<ScanMode?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var pasteInvalid by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf<PlaybackSyncSetup.Setup?>(null) }
    var joinedHost by remember { mutableStateOf<String?>(null) }
    var sendState by remember { mutableStateOf<SendState?>(null) }
    val canScan = getPlatform() == Platform.Android

    fun showOwnCode() {
        scope.launch { PlaybackSyncSetup.current(dataStore)?.let { showingCode = PlaybackSyncSetup.encode(it) } }
    }

    // A read from the scanner: a setup code joins, a receive code gets this device's setup sent to it.
    fun takeScan(text: String): Boolean {
        PlaybackSyncSetup.decode(text)?.let {
            scanning = null
            joining = it
            return true
        }
        val invite = SetupHandoff.decodeInvite(text) ?: return false
        scanning = null
        sendState = SendState.SENDING
        scope.launch {
            val setup = PlaybackSyncSetup.current(dataStore)
            sendState =
                when {
                    setup == null -> SendState.NOT_SET_UP
                    SetupHandoff.send(invite, setup) -> SendState.SENT
                    else -> SendState.FAILED
                }
        }
        return true
    }

    SettingItem(
        icon = SimpIcons.CloudSync,
        title = stringResource(Res.string.playback_sync_enable),
        subtitle = stringResource(Res.string.playback_sync_enable_description),
        switch =
            enabled to { on ->
                scope.launch { dataStore.putString(PlaybackSync.KEY_ENABLED, if (on) DataStoreManager.TRUE else DataStoreManager.FALSE) }
            },
    )
    // Outside the switch's fold: a new device has nothing set yet, and joining turns sync on.
    SettingItem(
        icon = SimpIcons.QrCode,
        title = stringResource(Res.string.playback_sync_receive_title),
        subtitle = joinedHost?.let { stringResource(Res.string.playback_sync_joined, it) } ?: stringResource(Res.string.playback_sync_receive_description),
        onClick = { receiving = true },
    )
    // The setup only matters while sync is on; the values stay saved while it is off.
    AnimatedVisibility(
        visible = enabled,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column {
            SettingItem(
                icon = SimpIcons.Link,
                title = stringResource(Res.string.playback_sync_server),
                subtitle = url?.takeIf { it.isNotBlank() } ?: notSet,
                onClick = { editing = PlaybackSync.KEY_URL },
            )
            SettingItem(
                icon = SimpIcons.Key,
                title = stringResource(Res.string.playback_sync_key),
                subtitle = if (secret.isNullOrBlank()) notSet else stringResource(Res.string.playback_sync_key_set),
                onClick = { editing = PlaybackSync.KEY_SECRET },
            )
            SettingItem(
                icon = SimpIcons.Devices,
                title = stringResource(Res.string.playback_sync_device_name),
                subtitle = name?.takeIf { it.isNotBlank() } ?: PlaybackSync.defaultDeviceName,
                onClick = { editing = PlaybackSync.KEY_DEVICE_NAME },
            )
            SettingItem(
                icon = SimpIcons.Refresh,
                title = stringResource(Res.string.playback_sync_test),
                subtitle =
                    stringResource(
                        when {
                            checking -> Res.string.playback_sync_testing
                            else ->
                                when (check) {
                                    null -> Res.string.playback_sync_test_hint
                                    PlaybackSync.ConnectionCheck.OK -> Res.string.playback_sync_ok
                                    PlaybackSync.ConnectionCheck.NOT_SET -> Res.string.playback_sync_incomplete
                                    PlaybackSync.ConnectionCheck.UNAUTHORIZED -> Res.string.playback_sync_unauthorized
                                    PlaybackSync.ConnectionCheck.FAILED -> Res.string.playback_sync_failed
                                }
                        },
                    ),
                onClick = {
                    if (!checking) {
                        scope.launch {
                            checking = true
                            check = PlaybackSync.checkConnection()
                            checking = false
                        }
                    }
                },
            )
            SettingItem(
                icon = SimpIcons.QrCode,
                title = stringResource(Res.string.playback_sync_show_code),
                subtitle =
                    if (url.isNullOrBlank() || secret.isNullOrBlank()) {
                        stringResource(Res.string.playback_sync_incomplete)
                    } else {
                        stringResource(Res.string.playback_sync_show_code_description)
                    },
                isEnable = !url.isNullOrBlank() && !secret.isNullOrBlank(),
                // A phone scans the other device's receive code, the way it signs in a laptop; a
                // computer has no camera, so it shows its own code for the other device to scan.
                onClick = { if (canScan) scanning = ScanMode.SEND else showOwnCode() },
            )
        }
    }

    showingCode?.let { code ->
        SetupCodeDialog(code = code, onDismiss = { showingCode = null })
    }

    scanning?.let { mode ->
        PlaybackSyncScanSheet(
            title =
                stringResource(
                    if (mode == ScanMode.SEND) Res.string.playback_sync_show_code else Res.string.playback_sync_scan_title,
                ),
            hint =
                stringResource(
                    if (mode == ScanMode.SEND) Res.string.playback_sync_send_scan_hint else Res.string.playback_sync_scan_hint,
                ),
            onScanned = ::takeScan,
            onDismiss = { scanning = null },
            secondary =
                if (mode == ScanMode.SEND) {
                    stringResource(Res.string.playback_sync_show_my_code) to {
                        scanning = null
                        showOwnCode()
                    }
                } else {
                    null
                },
        )
    }

    if (receiving) {
        ReceiveCodeDialog(
            canScan = canScan,
            onReceived = {
                receiving = false
                joining = it
            },
            onScan = {
                receiving = false
                scanning = ScanMode.JOIN
            },
            onPaste = {
                receiving = false
                pasteInvalid = false
                pasting = true
            },
            onDismiss = { receiving = false },
        )
    }

    sendState?.let { state ->
        AlertDialog(
            onDismissRequest = { if (state != SendState.SENDING) sendState = null },
            title = { Text(stringResource(Res.string.playback_sync_show_code), style = typo().titleMedium) },
            text = {
                Text(
                    stringResource(
                        when (state) {
                            SendState.SENDING -> Res.string.playback_sync_sending
                            SendState.SENT -> Res.string.playback_sync_sent
                            SendState.FAILED -> Res.string.playback_sync_send_failed
                            SendState.NOT_SET_UP -> Res.string.playback_sync_send_not_set_up
                        },
                    ),
                    style = typo().bodyMedium,
                )
            },
            confirmButton = {
                if (state != SendState.SENDING) {
                    TextButton(onClick = { sendState = null }) { Text(stringResource(Res.string.ok)) }
                }
            },
        )
    }

    if (pasting) {
        TextValueDialog(
            title = stringResource(Res.string.playback_sync_paste_title),
            initial = "",
            hidden = false,
            error = if (pasteInvalid) stringResource(Res.string.playback_sync_paste_invalid) else null,
            confirmLabel = stringResource(Res.string.playback_sync_join),
            onDismiss = { pasting = false },
            onSave = { value ->
                val setup = PlaybackSyncSetup.decode(value)
                if (setup == null) {
                    pasteInvalid = true
                } else {
                    pasting = false
                    joining = setup
                }
            },
        )
    }

    joining?.let { setup ->
        AlertDialog(
            onDismissRequest = { joining = null },
            title = { Text(stringResource(Res.string.playback_sync_join_title), style = typo().titleMedium) },
            text = { Text(stringResource(Res.string.playback_sync_join_message, setup.host), style = typo().bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    joining = null
                    check = null
                    joinedHost = setup.host
                    scope.launch { PlaybackSyncSetup.apply(dataStore, setup) }
                }) { Text(stringResource(Res.string.playback_sync_join)) }
            },
            dismissButton = { TextButton(onClick = { joining = null }) { Text(stringResource(Res.string.cancel)) } },
        )
    }

    editing?.let { key ->
        TextValueDialog(
            title =
                stringResource(
                    when (key) {
                        PlaybackSync.KEY_URL -> Res.string.playback_sync_server
                        PlaybackSync.KEY_SECRET -> Res.string.playback_sync_key
                        else -> Res.string.playback_sync_device_name
                    },
                ),
            initial =
                when (key) {
                    PlaybackSync.KEY_URL -> url
                    PlaybackSync.KEY_SECRET -> secret
                    else -> name
                }.orEmpty(),
            hidden = key == PlaybackSync.KEY_SECRET,
            onDismiss = { editing = null },
            onSave = { value ->
                scope.launch { dataStore.putString(key, value.trim()) }
                check = null
                editing = null
            },
        )
    }
}

@Composable
internal fun TextValueDialog(
    title: String,
    initial: String,
    hidden: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    error: String? = null,
    confirmLabel: String? = null,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = typo().titleMedium) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(confirmLabel ?: stringResource(Res.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

private enum class ScanMode { JOIN, SEND }

private enum class SendState { SENDING, SENT, FAILED, NOT_SET_UP }

/**
 * The joining side's code: this device opens a port and shows where it is, and a device that already
 * syncs scans it and sends its setup over the network. The port closes when the dialog does.
 */
@Composable
private fun ReceiveCodeDialog(
    canScan: Boolean,
    onReceived: (PlaybackSyncSetup.Setup) -> Unit,
    onScan: () -> Unit,
    onPaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    var invite by remember { mutableStateOf<String?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    val currentOnReceived by rememberUpdatedState(onReceived)
    val localNetwork = rememberLocalNetworkPermission()
    LaunchedEffect(Unit) {
        val receiver = SetupHandoff.Receiver.open()
        if (receiver == null) {
            noNetwork = true
            return@LaunchedEffect
        }
        try {
            invite = receiver.invite
            // Android 17+ asks for the local network before another device can reach this one.
            receiver.hosts.firstOrNull()?.let { localNetwork.requestIfNeeded("http://$it") }
            val setup = receiver.receive()
            currentOnReceived(setup)
        } finally {
            receiver.close()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.playback_sync_receive_title), style = typo().titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val code = invite
                when {
                    noNetwork -> {
                        Text(stringResource(Res.string.playback_sync_receive_no_network), style = typo().bodyMedium)
                    }

                    code == null -> {
                        CircularProgressIndicator()
                    }

                    else -> {
                        QrCodeImage(code, Modifier.size(240.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(Res.string.playback_sync_receive_how), style = typo().bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
        dismissButton = {
            Row {
                if (canScan) TextButton(onClick = onScan) { Text(stringResource(Res.string.playback_sync_scan_title)) }
                TextButton(onClick = onPaste) { Text(stringResource(Res.string.playback_sync_paste_title)) }
            }
        },
    )
}

/** The setup code as a QR, with a Copy button for a computer, which has no camera to scan it. */
@Composable
private fun SetupCodeDialog(
    code: String,
    onDismiss: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.playback_sync_code_title), style = typo().titleMedium) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                QrCodeImage(code, Modifier.size(240.dp))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.playback_sync_code_how), style = typo().bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.playback_sync_code_warning),
                    style = typo().bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ok)) } },
        dismissButton = {
            TextButton(onClick = {
                copyToClipboard("Sync setup code", code)
                copied = true
            }) {
                Text(stringResource(if (copied) Res.string.playback_sync_code_copied else Res.string.playback_sync_copy_code))
            }
        },
    )
}

/**
 * Home's slot for [ContinueFromDeviceCard]: it fades and folds away, so Home slides up into its place
 * instead of jumping. The last snapshot is kept so the card still has content while it leaves.
 */
@Composable
fun ContinueFromDeviceSlot(
    snapshot: PlaybackSnapshot?,
    onContinue: (PlaybackSnapshot) -> Unit,
    onDismiss: (PlaybackSnapshot) -> Unit,
) {
    val shown = remember { mutableStateOf(snapshot) }
    if (snapshot != null) shown.value = snapshot
    val spec = tween<IntSize>(durationMillis = 350, easing = FastOutSlowInEasing)
    AnimatedVisibility(
        visible = snapshot != null,
        enter = fadeIn(tween(350)) + expandVertically(spec),
        exit = fadeOut(tween(250)) + shrinkVertically(spec),
    ) {
        shown.value?.let { last ->
            Column {
                ContinueFromDeviceCard(last, onContinue = { onContinue(last) }, onDismiss = { onDismiss(last) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Home's offer to continue what another phone was playing. */
@Composable
private fun ContinueFromDeviceCard(
    snapshot: PlaybackSnapshot,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val track = snapshot.track ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f))
                .clickable(onClick = onContinue)
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
    ) {
        AsyncImage(
            model = track.thumbnails?.lastOrNull()?.url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.continue_from_device, snapshot.deviceName),
                style = typo().labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.title,
                style = typo().titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(
                        track.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() },
                        formatDuration(snapshot.positionMs),
                    ).joinToString(" · "),
                style = typo().bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onContinue) {
            Icon(SimpIcons.PlayArrow, contentDescription = stringResource(Res.string.continue_listening))
        }
        IconButton(onClick = onDismiss) {
            Icon(SimpIcons.Close, contentDescription = stringResource(Res.string.dismiss))
        }
    }
}
