package com.alike.masal.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.alike.masal.data.Channel
import com.alike.masal.data.USER_AGENT

@OptIn(UnstableApi::class)
fun buildPlayer(context: Context): ExoPlayer {
    val http = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
        // Ask Android for audio focus while playing, so another app (e.g. YouTube left playing
        // in the background) pauses, and Masal pauses in turn when another app takes over.
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .build()
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
