package com.alike.masal.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The household's trailer service (seerr.md): turns a YouTube video id into a stream address
 * Masal's own player can play (an HLS playlist). Optional; its address is set on the setup page.
 */
class TrailerService(private val http: OkHttpClient, private val settings: Settings) {
    private val found = ConcurrentHashMap<String, Pair<Long, String>>()

    val configured get() = settings.trailerService.isNotBlank()

    /** Whether a service answers at [base] (`GET /health`); the error text otherwise. */
    fun check(base: String): Result<Unit> = runCatching {
        val url = (base.trimEnd('/') + "/health").toHttpUrlOrNull() ?: error("invalid address")
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
        }
    }

    /** The stream address of YouTube video [videoId], or null; kept for 50 minutes (the addresses expire). */
    fun youTube(videoId: String): String? {
        val base = settings.trailerService.takeIf { it.isNotBlank() } ?: return null
        if (!VideoId.matches(videoId)) return null
        val now = System.currentTimeMillis()
        found[videoId]?.takeIf { now - it.first < KeepMs }?.let { return it.second }
        val url = (base + "/trailer").toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("v", videoId)?.build() ?: return null
        val stream = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val body = JSONObject(r.body?.string().orEmpty().ifBlank { "{}" })
                if (!r.isSuccessful) error(body.optString("error").ifBlank { "HTTP ${r.code}" })
                body.optString("hls").takeIf { it.startsWith("https://") } ?: error("no stream")
            }
        }.onFailure { android.util.Log.i("MasalTrailer", "service: ${it.message?.take(160)}") }.getOrNull() ?: return null
        found[videoId] = now to stream
        return stream
    }

    private companion object {
        val VideoId = Regex("^[A-Za-z0-9_-]{11}$")
        const val KeepMs = 50 * 60_000L
    }
}
