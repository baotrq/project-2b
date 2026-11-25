package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/**
 * Fork: scans a playback-sync code (a setup code to join, or another device's receive code to send
 * this device's setup to). Android only: Desktop has no camera and pastes or shows a code instead, so
 * its actual is never shown. [onScanned] gets every QR read and returns whether it was taken; the
 * sheet stops handing reads on once one is, and the caller closes it. [secondary] is an optional
 * extra button under the viewfinder, as label to action.
 */
@Composable
expect fun PlaybackSyncScanSheet(
    title: String,
    hint: String,
    onScanned: (String) -> Boolean,
    onDismiss: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null,
)
