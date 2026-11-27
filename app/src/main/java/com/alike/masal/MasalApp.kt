package com.alike.masal

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.alike.masal.data.Channel
import com.alike.masal.data.IptvRepository
import com.alike.masal.data.PlexClient
import com.alike.masal.data.PlexItem
import com.alike.masal.data.Programme
import com.alike.masal.data.Settings
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MasalApp : Application() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.alike.masal.data.Language.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        // The restart guard's own process needs none of the app's data layer (Guard.kt).
        if (isGuardProcess()) return
        com.alike.masal.data.Language.init(this)
        Graph.init(this)
        GuardLink.install(this)
    }
}

object Graph {
    lateinit var settings: Settings
    lateinit var http: OkHttpClient
    lateinit var plex: PlexClient
    lateinit var iptv: IptvRepository
    lateinit var seerr: com.alike.masal.data.SeerrClient
    lateinit var trailers: com.alike.masal.data.TrailerService
    private val profileClients = java.util.concurrent.ConcurrentHashMap<String, PlexClient>()

    /**
     * The Plex client for a profile id (an item's [PlexItem.profile]): requests go out as that Home
     * member. The main account's client for null or a profile that was removed.
     */
    fun plexFor(profileId: String?): PlexClient {
        if (profileId == null) return plex
        val p = settings.profiles.firstOrNull { it.id == profileId } ?: return plex
        return profileClients[profileId]?.takeIf { it.profile == p } ?: PlexClient(http, settings, p).also { profileClients[profileId] = it }
    }

    fun plexFor(item: PlexItem?): PlexClient = plexFor(item?.profile)

    fun init(app: Application) {
        settings = Settings(app)
        http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .addNetworkInterceptor(::dropSecretsOnRedirect)
            .build()
        plex = PlexClient(http, settings)
        profileClients.clear()
        iptv = IptvRepository(http, settings, app.cacheDir)
        seerr = com.alike.masal.data.SeerrClient(http, settings, com.alike.masal.data.SeerrCache(java.io.File(app.cacheDir, "seerr")))
        trailers = com.alike.masal.data.TrailerService(http)
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
    /** A title (ratingKey, profile) to open on Masal's pages after leaving the player from a person page (people.md). */
    var openAfter: Pair<String, String?>? = null
    /** Counts hand-overs: bumped whenever the player's data is set, so a closing player clears only its own. */
    var handOverToken = 0
        private set

    /** Marks new player data as handed over; call after setting the fields. */
    fun handedOverNow() { handOverToken++ }

    /** Drops the data of hand-over [token], unless a newer player was handed its own since. */
    fun clear(token: Int) {
        if (token != handOverToken) return
        channels = emptyList(); groups = emptyList(); epg = emptyMap(); plexItem = null; listName = ""; index = 0
    }

    /** Registered by every preview player; all are stopped before full-screen playback starts. */
    val previews = mutableSetOf<() -> Unit>()
    fun stopPreviews() = previews.toList().forEach { it() }

    /** Running previews that can give their stream to the full-screen player, asked by source key. */
    val handovers = mutableSetOf<(String) -> androidx.media3.exoplayer.ExoPlayer?>()
    /** A preview's running live stream, for PlayerActivity to continue without reconnecting. */
    var handedOver: androidx.media3.exoplayer.ExoPlayer? = null
    /**
     * The live player's stream given back to Home's preview after zooming out: source key and player.
     * Compose state, so the preview notices it whenever it arrives.
     */
    var handedBack by androidx.compose.runtime.mutableStateOf<Pair<String, androidx.media3.exoplayer.ExoPlayer>?>(null)
        private set

    /** Takes the running stream of the preview playing [sourceKey], if one does. */
    fun takePreview(sourceKey: String) = handovers.toList().firstNotNullOfOrNull { it(sourceKey) }

    fun giveBack(sourceKey: String, player: androidx.media3.exoplayer.ExoPlayer) {
        handedBack = sourceKey to player
        // Nobody took it (the focus moved on meanwhile): release it rather than let it play unseen.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (handedBack?.second === player) { handedBack = null; player.release() }
        }, 3_000)
    }

    /** The given-back stream for [sourceKey], once. */
    fun takeBack(sourceKey: String?): androidx.media3.exoplayer.ExoPlayer? =
        handedBack?.takeIf { it.first == sourceKey }?.second?.also { handedBack = null }
}

/**
 * Network interceptor: OkHttp removes only `Authorization` when a redirect leads elsewhere. The
 * Plex token and the Seerr key go only to the host (and scheme) they were sent to, never to a
 * redirect's target.
 */
fun dropSecretsOnRedirect(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
    val request = chain.request()
    val first = chain.call().request().url
    val url = request.url
    if (url.host == first.host && url.scheme == first.scheme) return chain.proceed(request)
    val clean = request.newBuilder()
    for (name in SecretHeaders) clean.removeHeader(name)
    return chain.proceed(clean.build())
}

private val SecretHeaders = listOf("X-Plex-Token", "X-Api-Key", "X-API-User")
