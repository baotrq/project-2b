package com.maxrave.simpmusic.wear

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import com.maxrave.simpmusic.wear.ui.SimpMusicWearTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.maxrave.simpmusic.wear.ui.CollectionScreen
import com.maxrave.simpmusic.wear.ui.LibraryScreen
import com.maxrave.simpmusic.wear.ui.PlayerScreen
import com.maxrave.simpmusic.wear.ui.QueueScreen

class WearActivity : ComponentActivity() {
    /**
     * Back to the watch face after [IDLE_TIMEOUT_MS] with no touch, swipe or crown turn, on every
     * screen and even while music plays: the phone keeps playing, and the app is a remote, not a
     * screen to leave on. Counted here rather than in Compose so every input path resets it.
     */
    private val idleHandler = Handler(Looper.getMainLooper())
    private val returnToWatchFace = Runnable { finish() }

    private fun restartIdleTimer() {
        idleHandler.removeCallbacks(returnToWatchFace)
        idleHandler.postDelayed(returnToWatchFace, IDLE_TIMEOUT_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WearApp() }
    }

    override fun onStart() {
        super.onStart()
        PhoneLink.attach(this)
    }

    override fun onResume() {
        super.onResume()
        restartIdleTimer()
    }

    override fun onPause() {
        idleHandler.removeCallbacks(returnToWatchFace)
        super.onPause()
    }

    override fun onStop() {
        PhoneLink.detach()
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        restartIdleTimer()
        return super.dispatchTouchEvent(ev)
    }

    /** Rotary input (the crown) arrives here, not as a touch. */
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        restartIdleTimer()
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        restartIdleTimer()
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 60_000L
    }
}

private object Routes {
    const val PLAYER = "player"
    const val QUEUE = "queue"
    const val LIBRARY = "library"
    const val COLLECTION = "collection/{id}"

    fun collection(id: String) = "collection/${Uri.encode(id)}"
}

@Composable
private fun WearApp() {
    SimpMusicWearTheme {
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            // Library opens by itself at most once per launch (when the phone has nothing playing),
            // and never after the user has gone there and come back.
            var libraryOpened by rememberSaveable { mutableStateOf(false) }
            SwipeDismissableNavHost(navController = nav, startDestination = Routes.PLAYER) {
                composable(Routes.PLAYER) {
                    PlayerScreen(
                        onOpenQueue = { nav.navigate(Routes.QUEUE) },
                        onOpenLibrary = {
                            libraryOpened = true
                            nav.navigate(Routes.LIBRARY)
                        },
                        autoOpenLibrary = !libraryOpened,
                    )
                }
                composable(Routes.QUEUE) {
                    QueueScreen(onPlayed = { nav.popBackStack(Routes.PLAYER, inclusive = false) })
                }
                composable(Routes.LIBRARY) {
                    LibraryScreen(onOpen = { id -> nav.navigate(Routes.collection(id)) })
                }
                composable(Routes.COLLECTION) { entry ->
                    CollectionScreen(
                        collectionId = entry.arguments?.getString("id").orEmpty(),
                        onPlayed = { nav.popBackStack(Routes.PLAYER, inclusive = false) },
                    )
                }
            }
        }
    }
}
