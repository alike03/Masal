package com.alike.masal

import android.app.Application
import com.alike.masal.data.Channel
import com.alike.masal.data.IptvRepository
import com.alike.masal.data.PlexClient
import com.alike.masal.data.PlexItem
import com.alike.masal.data.Programme
import com.alike.masal.data.Settings
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MasalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

object Graph {
    lateinit var settings: Settings
    lateinit var http: OkHttpClient
    lateinit var plex: PlexClient
    lateinit var iptv: IptvRepository

    fun init(app: Application) {
        settings = Settings(app)
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        plex = PlexClient(http, settings)
        iptv = IptvRepository(http, settings, app.cacheDir)
    }
}

/** Hand-over to the player; channel lists are too large for Intent extras. */
object Playback {
    var channels: List<Channel> = emptyList()
    var listName: String = ""
    /** All categories for the channel list inside the player. */
    var groups: List<Pair<String, List<Channel>>> = emptyList()
    var index: Int = 0
    var epg: Map<String, List<Programme>> = emptyMap()
    var plexItem: PlexItem? = null
    /** Registered by every preview player; all are stopped before full-screen playback starts. */
    val previews = mutableSetOf<() -> Unit>()
    fun stopPreviews() = previews.toList().forEach { it() }
}
