package com.alike.masal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** What the embedded YouTube player reports. */
enum class TrailerState { Loading, Playing, Paused, Ended, Failed }

/**
 * YouTube's own embedded player (IFrame Player API) in a WebView, for Seerr trailers (seerr.md).
 * The page's base URL is `https://<application id>`, which YouTube requires as the Referer to
 * identify the app; nothing is drawn over the player. Focus stays in Compose: the remote controls
 * the player through [toggle].
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeTrailer(context: Context, videoId: String, private val onState: (TrailerState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    val view: WebView = WebView(context).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        isFocusableInTouchMode = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        addJavascriptInterface(Bridge(), "Masal")
        val origin = "https://" + context.packageName
        loadDataWithBaseURL("$origin/", page(videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }, origin), "text/html", "utf-8", null)
    }

    fun toggle() = view.evaluateJavascript("toggle()", null)

    fun destroy() {
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun state(s: Int) = main.post {
            onState(when (s) { 1, 3 -> TrailerState.Playing; 2 -> TrailerState.Paused; 0 -> TrailerState.Ended; else -> TrailerState.Loading })
        }.let { }

        // 2, 5, 100, 101, 150, 153: bad id, HTML5 error, removed, embedding not allowed, no client identity.
        @JavascriptInterface
        fun error(@Suppress("UNUSED_PARAMETER") code: Int) = main.post { onState(TrailerState.Failed) }.let { }
    }

    private fun page(id: String, origin: String) = """<!doctype html><html><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;left:0;top:0;width:100%;height:100%}</style>
</head><body><div id="p"></div><script>
var player;
var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';document.head.appendChild(tag);
function onYouTubeIframeAPIReady(){player=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
 playerVars:{autoplay:1,controls:0,playsinline:1,rel:0,iv_load_policy:3,origin:'$origin'},
 events:{onReady:function(e){e.target.playVideo();},onStateChange:function(e){Masal.state(e.data);},onError:function(e){Masal.error(e.data);}}});}
function toggle(){if(!player||!player.getPlayerState)return;if(player.getPlayerState()==1)player.pauseVideo();else player.playVideo();}
</script></body></html>"""
}

/** The trailer's WebView in this place; it moves between the page and full screen without restarting. */
@Composable
fun TrailerView(trailer: YouTubeTrailer?, modifier: Modifier = Modifier) {
    if (trailer == null) {
        // JVM tests and devices without previews: a black box where the player would be.
        Box(modifier.background(androidx.compose.ui.graphics.Color.Black))
        return
    }
    AndroidView(factory = { (trailer.view.parent as? ViewGroup)?.removeView(trailer.view); trailer.view }, modifier = modifier)
}

/** The trailer over the whole screen: OK or play/pause pauses and plays, Back returns to the page. */
@Composable
fun TrailerFullScreen(trailer: YouTubeTrailer?, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black).focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                            if (e.type == KeyEventType.KeyUp) trailer?.toggle()
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
        ) { TrailerView(trailer, Modifier.fillMaxSize()) }
    }
}
