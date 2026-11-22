package com.alike.masal.ui

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.focusable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/** What a trailer player reports. */
enum class TrailerState { Loading, Playing, Paused, Ended, Failed }

/** A trailer on the Seerr title page (seerr.md), played in Masal's own player. */
interface Trailer {
    /** The view that shows the picture. */
    val view: android.view.View
    fun toggle()
    fun pause()
    fun play()
    fun destroy()
}

/**
 * A trailer in Masal's own player, in a PlayerView that keeps the picture's aspect: Plex's online
 * trailer (an MP4 address from Plex's catalogue) or a YouTube trailer from the trailer service (an
 * HLS playlist, [hls]).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoTrailer(context: Context, url: String, hls: Boolean, private val onState: (TrailerState) -> Unit) : Trailer {
    private var firstFrame = false
    private val player = com.alike.masal.player.buildPlayer(context).apply {
        setMediaItem(androidx.media3.common.MediaItem.Builder().setUri(url)
            .apply { if (hls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }.build())
        addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) onState(TrailerState.Ended)
            }
            // Playing only once the first frame is on screen, so the loading bar stays until then.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) onState(TrailerState.Paused) else if (firstFrame) onState(TrailerState.Playing)
            }
            override fun onRenderedFirstFrame() {
                firstFrame = true
                if (isPlaying) onState(TrailerState.Playing)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.i("MasalTrailer", "trailer error ${error.errorCodeName}")
                onState(TrailerState.Failed)
            }
        })
        prepare()
        playWhenReady = true
    }

    override val view: android.view.View = androidx.media3.ui.PlayerView(context).apply {
        useController = false
        setShutterBackgroundColor(Color.BLACK)
        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        player = this@VideoTrailer.player
        isFocusable = false
    }

    override fun toggle() { if (player.isPlaying) player.pause() else player.play() }
    override fun pause() = player.pause()
    override fun play() = player.play()
    override fun destroy() {
        (view.parent as? ViewGroup)?.removeView(view)
        (view as androidx.media3.ui.PlayerView).player = null
        player.release()
    }
}

/** The trailer's picture in this place. */
@Composable
fun TrailerView(trailer: Trailer?, modifier: Modifier = Modifier) {
    if (trailer == null) {
        // JVM tests and devices without previews: a black box where the player would be.
        Box(modifier.background(androidx.compose.ui.graphics.Color.Black))
        return
    }
    AndroidView(factory = { (trailer.view.parent as? ViewGroup)?.removeView(trailer.view); trailer.view }, modifier = modifier)
}

/**
 * The trailer in its own full-screen window (seerr.md): over the whole screen, side rail strip
 * included, and outside the page's drawing. [slide] is 1 when it fills the screen and 0 when it has
 * slid up out of view (read in placement, so the slide does not recompose). Black with the loading
 * bar until the picture starts ([loading]). OK or play/pause pauses and plays; Down or Back hide it
 * ([onHide]) and show the page's details.
 */
@Composable
fun TrailerWindow(trailer: Trailer?, loading: Boolean, slide: () -> Float, onHide: () -> Unit) {
    Dialog(onHide, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        (androidx.compose.ui.platform.LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let {
            it.setDimAmount(0f)
            // The slide is the animation: no platform fade or zoom on top of it.
            it.setWindowAnimations(0)
        }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
        Box(
            Modifier.fillMaxSize()
                .layout { m, c ->
                    val p = m.measure(c)
                    layout(p.width, p.height) { p.place(0, ((slide() - 1f) * p.height).roundToInt()) }
                }
                .background(androidx.compose.ui.graphics.Color.Black).focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                            if (e.type == KeyEventType.KeyUp) trailer?.toggle()
                            true
                        }
                        Key.DirectionDown -> { if (e.type == KeyEventType.KeyDown) onHide(); true }
                        else -> false
                    }
                }
                .focusable(),
        ) {
            TrailerView(trailer, Modifier.fillMaxSize())
            // Infinite animation: only on the device (tests have no video and would never go idle).
            if (loading && VideoPreviews.enabled) com.alike.masal.player.LoadingBar(Modifier.align(Alignment.BottomCenter))
        }
    }
}
