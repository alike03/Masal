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

/** What the embedded YouTube player reports. */
enum class TrailerState { Loading, Playing, Paused, Ended, Failed }

/**
 * YouTube's own embed page (`youtube.com/embed/<id>`) in a WebView, for Seerr trailers
 * (seerr.md). YouTube requires the app's identity as the Referer: the page is requested with
 * `Referer: https://<application id>`. Nothing is drawn over the player; focus stays in Compose
 * and the remote controls the page's video element through [toggle]. Player events and console
 * messages are logged under tag `MasalTrailer` (no addresses with logins pass through here).
 */
@SuppressLint("SetJavaScriptEnabled")
class YouTubeTrailer(context: Context, videoId: String, private val onState: (TrailerState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val id = videoId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    private val referer = "https://" + context.packageName

    val view: WebView = WebView(context).apply {
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
        Log.i(Tag, "load $id")
        loadUrl(
            "https://www.youtube.com/embed/$id?autoplay=1&controls=0&playsinline=1&rel=0&iv_load_policy=3&fs=0",
            mapOf("Referer" to "$referer/"),
        )
    }

    fun toggle() = view.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return;if(v.paused)v.play();else v.pause();})()", null)

    fun destroy() {
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
var p=v.play();if(p&&p.catch)p.catch(function(err){Masal.state('blocked:'+err);});return true;}
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
fun TrailerView(trailer: YouTubeTrailer?, modifier: Modifier = Modifier) {
    if (trailer == null) {
        // JVM tests and devices without previews: a black box where the player would be.
        Box(modifier.background(androidx.compose.ui.graphics.Color.Black))
        return
    }
    AndroidView(factory = { (trailer.view.parent as? ViewGroup)?.removeView(trailer.view); trailer.view }, modifier = modifier)
}
