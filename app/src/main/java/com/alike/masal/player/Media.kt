package com.alike.masal.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.alike.masal.data.Channel
import com.alike.masal.data.USER_AGENT

/** Media playback with audio focus: another app pauses while Masal plays, and Masal pauses in turn. */
val mediaAudio: AudioAttributes =
    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()

/**
 * [live]: starts a channel after about 1 s of buffer instead of ExoPlayer's 2.5 s, so switching
 * channels is quicker; after a stall it still waits for the default 5 s, so a weak stream does not
 * stutter. Plex and the previews keep the defaults.
 * [audioFocus] false: for a player that plays muted next to the audible one (the live player's
 * next channel), which must not take the audio focus from it (that would pause it).
 */
/** Most a player may buffer ahead, in bytes (Java heap). */
const val MAX_BUFFER_BYTES = 100 * 1024 * 1024

/** Most a Home preview may buffer: it runs next to Home's images and may last for hours. */
const val PREVIEW_BUFFER_BYTES = 32 * 1024 * 1024

/**
 * [preview]: a Home preview (or a live channel continued from one): at most 30 s and
 * [PREVIEW_BUFFER_BYTES] ahead, and it starts after a second of picture like live.
 */
@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, live: Boolean = false, audioFocus: Boolean = true, preview: Boolean = false): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
    val builder = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
        // Ask Android for audio focus while playing, so another app (e.g. YouTube left playing
        // in the background) pauses, and Masal pauses in turn when another app takes over.
        .setAudioAttributes(mediaAudio, /* handleAudioFocus = */ audioFocus)
    // The buffer lives on the Java heap. Media3's default allows about 144 MB, which a high-bitrate
    // film fills, leaving Masal almost no room under its 192 MB limit: capped at 100 MB.
    val load = DefaultLoadControl.Builder().setTargetBufferBytes(if (preview) PREVIEW_BUFFER_BYTES else MAX_BUFFER_BYTES)
    if (preview) {
        load.setBufferDurationsMs(
            /* minBufferMs = */ 15_000,
            /* maxBufferMs = */ 30_000,
            /* bufferForPlaybackMs = */ 1_000,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
    } else if (live) {
        load.setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            /* bufferForPlaybackMs = */ 1_000,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        )
    }
    builder.setLoadControl(load.build())
    return builder.build()
}

fun channelItem(ch: Channel): MediaItem {
    val b = MediaItem.Builder().setUri(ch.url)
    if (ch.url.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8)
    return b.build()
}

/** Logs remote keys (tag MasalKeys) to see what a TV remote sends over HDMI-CEC. */
fun logKey(e: android.view.KeyEvent) {
    android.util.Log.d(
        "MasalKeys",
        "${android.view.KeyEvent.keyCodeToString(e.keyCode)} ${if (e.action == 0) "down" else "up"} " +
            "repeat=${e.repeatCount} long=${e.isLongPress} device=${e.device?.name}",
    )
}
