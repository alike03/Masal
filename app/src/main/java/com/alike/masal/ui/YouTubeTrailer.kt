package com.alike.masal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

/** A trailer on the Seerr title page (seerr.md): Plex's online trailer, else YouTube's player. */
interface Trailer {
    /** The view that shows the picture. */
    val view: android.view.View
    fun toggle()
    fun pause()
    fun play()
    fun destroy()
}

/**
 * Plex's online trailer (an MP4 address from Plex's catalogue) in Masal's own player, in a
 * PlayerView that keeps the picture's aspect.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlexTrailer(context: Context, url: String, private val onState: (TrailerState) -> Unit) : Trailer {
    private var firstFrame = false
    private val player = com.alike.masal.player.buildPlayer(context).apply {
        setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
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
                Log.i("MasalTrailer", "plex trailer error ${error.errorCodeName}")
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
        player = this@PlexTrailer.player
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

/**
 * YouTube's own embed page (`youtube.com/embed/<id>`) in a WebView, for Seerr trailers
 * (seerr.md). YouTube requires the app's identity as the Referer: the page is requested with
 * `Referer: https://<application id>`. Nothing is drawn over the player; focus stays in Compose
 * and the remote controls the page's video element through [toggle]. Player events and console
 * messages are logged under tag `MasalTrailer` (no addresses with logins pass through here).
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeTrailer(context: Context, videoId: String, private val onState: (TrailerState) -> Unit) : Trailer {
    private val main = Handler(Looper.getMainLooper())
    // Not called `id`: inside WebView.apply that name is the view's own id (always -1).
    private val videoKey = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    private val referer = "https://" + context.packageName

    override val view: WebView = WebView(context).apply {
        // DevTools over ADB only when switched on with ADB itself: `setprop log.tag.MasalTrailer DEBUG`.
        if (Log.isLoggable(Tag, Log.DEBUG)) WebView.setWebContentsDebuggingEnabled(true)
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        isFocusableInTouchMode = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        addJavascriptInterface(Bridge(), "Masal")
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i(Tag, "console ${m.messageLevel()}: ${m.message().take(300)}")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            // Links in the player (title, YouTube logo) must not navigate away inside Masal.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                !request.url.toString().startsWith("https://www.youtube.com/embed/")

            override fun onPageFinished(view: WebView, url: String) {
                Log.i(Tag, "page loaded")
                view.evaluateJavascript(Watch, null)
            }
        }
        Log.i(Tag, "load $videoKey")
        loadUrl(
            "https://www.youtube.com/embed/$videoKey?autoplay=1&controls=0&playsinline=1&rel=0&iv_load_policy=3&fs=0",
            mapOf("Referer" to "$referer/"),
        )
    }

    override fun toggle() = view.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return;if(v.paused)v.play();else v.pause();})()", null)
    override fun pause() = view.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.pause();})()", null)
    override fun play() = view.evaluateJavascript("(function(){var v=document.querySelector('video');if(v)v.play();})()", null)

    override fun destroy() {
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun state(s: String) {
            Log.i(Tag, "state $s")
            main.post {
                onState(when (s) { "playing" -> TrailerState.Playing; "pause" -> TrailerState.Paused; "ended" -> TrailerState.Ended; else -> TrailerState.Loading })
            }
        }

        @JavascriptInterface
        fun stats(s: String) = Log.i(Tag, "video $s").let { }

        @JavascriptInterface
        fun error(what: String) {
            Log.i(Tag, "error ${what.take(200)}")
            main.post { onState(TrailerState.Failed) }
        }
    }

    private companion object {
        const val Tag = "MasalTrailer"

        /**
         * Runs in the embed page: reports the video element's events, starts it, and reports YouTube's
         * error screen (embedding not allowed, video unavailable) or a page without a video after 8 s.
         */
        const val Watch = """(function(){
var hooked=false;
function hook(){var v=document.querySelector('video');if(!v||hooked)return !!v;hooked=true;
['playing','pause','ended'].forEach(function(e){v.addEventListener(e,function(){Masal.state(e);});});
var p=v.play();if(p&&p.catch)p.catch(function(err){Masal.state('blocked:'+err);});
var n=0;var st=setInterval(function(){n++;if(n>15){clearInterval(st);return;}var q=v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():{};
Masal.stats('t='+v.currentTime.toFixed(1)+' size='+v.videoWidth+'x'+v.videoHeight+' frames='+q.totalVideoFrames+' dropped='+q.droppedVideoFrames+' paused='+v.paused+' ready='+v.readyState);},2000);
return true;}
var tries=0;var t=setInterval(function(){tries++;
var err=document.querySelector('.ytp-error');
if(err&&err.offsetParent!==null){clearInterval(t);Masal.error(err.innerText||'ytp-error');return;}
if(hook()&&tries>2){clearInterval(t);return;}
if(tries>16){clearInterval(t);if(!hooked)Masal.error('no video');}},500);
})();"""
    }
}

/** The trailer's WebView in this place. */
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
