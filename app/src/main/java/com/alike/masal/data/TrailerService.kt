package com.alike.masal.data

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube trailers (seerr.md): turns a YouTube video id into a stream address Masal's own player
 * can play (an HLS playlist). [resolve] asks YouTube itself through NewPipe Extractor; tests pass
 * their own.
 */
class TrailerService(private val resolve: (String) -> String?) {
    constructor(http: OkHttpClient) : this(newPipe(http))

    private val found = ConcurrentHashMap<String, Pair<Long, String>>()

    /** The stream address of YouTube video [videoId], or null; kept for 50 minutes (the addresses expire). */
    fun youTube(videoId: String): String? {
        if (!VideoId.matches(videoId)) return null
        val now = System.currentTimeMillis()
        found[videoId]?.takeIf { now - it.first < KeepMs }?.let { return it.second }
        val stream = runCatching { resolve(videoId)?.takeIf { it.startsWith("https://") } ?: error("no stream") }
            .onFailure { android.util.Log.i("MasalTrailer", "youtube: ${it.javaClass.simpleName}: ${it.message?.take(160)}") }
            .getOrNull() ?: return null
        found[videoId] = now to stream
        return stream
    }

    private companion object {
        val VideoId = Regex("^[A-Za-z0-9_-]{11}$")
        const val KeepMs = 50 * 60_000L

        @Volatile var ready = false

        /** NewPipe Extractor over Masal's OkHttp; the HLS playlist of YouTube's visionOS client. */
        fun newPipe(http: OkHttpClient): (String) -> String? = { id ->
            if (!ready) synchronized(this) { if (!ready) { NewPipe.init(OkHttpDownloader(http)); ready = true } }
            val ex = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
            ex.fetchPage()
            ex.hlsUrl.takeIf { it.isNotBlank() }
        }
    }
}

/** NewPipe Extractor's network access through OkHttp. */
private class OkHttpDownloader(private val http: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(
            request.headers()["Content-Type"]?.firstOrNull()?.toMediaTypeOrNull())
        val b = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
        request.headers().forEach { (name, values) ->
            b.removeHeader(name)
            values.forEach { b.addHeader(name, it) }
        }
        http.newCall(b.build()).execute().use { r ->
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}
