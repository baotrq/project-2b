package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

// Desktop has no camera: it pastes or shows a code instead, so this is never opened.
@Composable
actual fun PlaybackSyncScanSheet(
    title: String,
    hint: String,
    onScanned: (String) -> Boolean,
    onDismiss: () -> Unit,
    secondary: Pair<String, () -> Unit>?,
) {}
