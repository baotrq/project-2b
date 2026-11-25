package com.maxrave.simpmusic.expect.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.maxrave.simpmusic.extension.findActivity
import com.maxrave.simpmusic.ui.component.QrScanner
import com.maxrave.simpmusic.ui.component.rememberSurfaceDarkColors
import com.maxrave.simpmusic.ui.icon.Close
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.cancel
import simpmusic.composeapp.generated.resources.login_sync_allow_camera
import simpmusic.composeapp.generated.resources.login_sync_no_camera
import simpmusic.composeapp.generated.resources.login_sync_open_settings
import simpmusic.composeapp.generated.resources.playback_sync_scan_camera_blocked
import simpmusic.composeapp.generated.resources.playback_sync_scan_camera_needed
import simpmusic.composeapp.generated.resources.playback_sync_scan_not_a_code

private enum class Camera { ASK, BLOCKED, GRANTED, UNAVAILABLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun PlaybackSyncScanSheet(
    title: String,
    hint: String,
    onScanned: (String) -> Boolean,
    onDismiss: () -> Unit,
    secondary: Pair<String, () -> Unit>?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = rememberSurfaceDarkColors()

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var camera by remember { mutableStateOf(if (granted()) Camera.GRANTED else Camera.ASK) }
    // The last QR read was something else; cleared by the next read.
    var wrongCode by remember { mutableStateOf(false) }
    // Once a code is taken, later frames (the scanner keeps reporting it) must not be handed on again.
    var done by remember { mutableStateOf(false) }

    val permissionLauncher =
        // Camera plus, on Android 17+, the local network: sending a setup to another device's receive
        // code connects to it directly, and without that permission the connection only times out.
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val ok = result[Manifest.permission.CAMERA] == true
            camera =
                when {
                    ok -> Camera.GRANTED
                    // Rationale is false after a denial only when the system will not ask again.
                    !context.findActivity().shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> Camera.BLOCKED
                    else -> Camera.ASK
                }
        }
    // Back from the system settings page with the camera now allowed.
    LifecycleResumeEffect(camera) {
        if (camera == Camera.BLOCKED && granted()) camera = Camera.GRANTED
        onPauseOrDispose { }
    }

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        contentColor = Color.Transparent,
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .5f),
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            colors = CardDefaults.cardColors().copy(containerColor = colors.container),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .width(60.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(colors.handle),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = typo().titleMedium,
                        color = colors.content,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = ::close) {
                        Icon(imageVector = SimpIcons.Close, contentDescription = stringResource(Res.string.cancel), tint = colors.content)
                    }
                }
                Text(
                    text = if (wrongCode) stringResource(Res.string.playback_sync_scan_not_a_code) else hint,
                    style = typo().bodySmall,
                    color = if (wrongCode) MaterialTheme.colorScheme.error else colors.subtitle,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier =
                        Modifier
                            .padding(horizontal = 24.dp)
                            .widthIn(max = 340.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(if (camera == Camera.GRANTED) Color.Black else colors.disabled.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    when (camera) {
                        Camera.GRANTED -> {
                            QrScanner(
                                onResult = { text ->
                                    if (!done) {
                                        if (onScanned(text)) done = true else wrongCode = true
                                    }
                                },
                                onCameraUnavailable = { camera = Camera.UNAVAILABLE },
                                modifier = Modifier.fillMaxSize(),
                            )
                            ViewfinderCorners(Modifier.fillMaxSize().padding(20.dp))
                        }

                        Camera.ASK -> {
                            Status(stringResource(Res.string.playback_sync_scan_camera_needed), progress = false) {
                                SheetButton(stringResource(Res.string.login_sync_allow_camera)) {
                                    permissionLauncher.launch(loginSyncPermissions().toTypedArray())
                                }
                            }
                        }

                        Camera.BLOCKED -> {
                            Status(stringResource(Res.string.playback_sync_scan_camera_blocked), progress = false) {
                                SheetButton(stringResource(Res.string.login_sync_open_settings)) { context.openAppSettings() }
                            }
                        }

                        Camera.UNAVAILABLE -> {
                            Status(stringResource(Res.string.login_sync_no_camera), progress = false)
                        }
                    }
                }
                secondary?.let { (label, action) ->
                    TextButton(onClick = action, modifier = Modifier.padding(top = 12.dp)) {
                        Text(label, style = typo().labelLarge, color = colors.content)
                    }
                }
            }
        }
    }
}
