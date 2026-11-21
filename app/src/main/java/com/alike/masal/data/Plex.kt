package com.alike.masal.data

import android.net.Uri
import com.alike.masal.R
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class PlexItem(
    val ratingKey: String,
    val type: String, // movie, show, season, episode
    val title: String,
    val subtitle: String,
    val poster: String?,
    val art: String?,
    val still: String?,
    val viewOffset: Long,
    val duration: Long,
    val partKey: String?,
    val summary: String?,
    val year: Int? = null,
    val contentRating: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val tagline: String? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val index: Int = 0,
    val parentIndex: Int = 0,
    val episodeTitle: String? = null,
    val childCount: Int = 0,
    val leafCount: Int = 0,
    val viewedLeafCount: Int = 0,
    /** Title artwork ("clearLogo"), for episodes the show's. */
    val logo: String? = null,
    val released: String? = null, // yyyy-MM-dd
    val addedAt: Long = 0,
    val lastViewedAt: Long = 0,
    val titleSort: String = "",
    val cast: List<String> = emptyList(),
    val partId: Long? = null,
    val streams: List<PlexStream> = emptyList(),
    val sectionId: String? = null,
    /** Plex's background tint (topLeft, topRight, bottomRight, bottomLeft) as ARGB. */
    val blur: List<Int> = emptyList(),
    /** Id of the profile the item was loaded as (see profiles.md); null for the main account. */
    val profile: String? = null,
    /** Cast, then directors, writers and producers, as Plex lists them (people.md). */
    val people: List<CastMember> = emptyList(),
    /** The title's TMDB id from Plex's metadata (for an episode the episode's own). */
    val tmdbId: Int? = null,
) {
    val playable get() = type == "movie" || type == "episode" || type == "clip"
    val progress get() = if (duration > 0 && viewOffset > 0) viewOffset.toFloat() / duration else 0f
}

data class PlexSection(val key: String, val title: String, val type: String, val art: String?)

/** Video (1), audio (2) or subtitle (3) stream of a file. External subtitles have a [key]. */
data class PlexStream(
    val id: Long,
    val type: Int,
    val codec: String,
    val language: String,
    val languageCode: String,
    val title: String,
    val key: String?,
    val selected: Boolean,
    val forced: Boolean,
)

/** A row of titles as Plex groups them (Continue Watching, Recently Added, a collection, ...). */
data class PlexHub(val id: String, val title: String, val items: List<PlexItem>, val owner: String? = null)

/** A member of the main account's Plex Home, added so Home can show rows loaded as that member. */
data class PlexProfile(val id: String, val name: String, val token: String)

/** A member of the main account's Plex Home as plex.tv lists it. */
data class PlexHomeUser(val id: String, val name: String, val protected: Boolean)

/** Thrown when the server no longer accepts a profile's token (removed from Plex Home, PIN changed). */
class ProfileRejectedException : Exception("profile signed out")

/** Thrown when plex.tv refuses a Home member's PIN. */
class WrongPinException : Exception("wrong PIN")

private val SearchTypes = setOf("movie", "show", "season", "episode")

/** One result of Plex's online subtitle search; [title] is the release name, which tells the results apart. */
data class SubtitleResult(val key: String, val codec: String, val language: String, val title: String, val provider: String,
    val score: Double, val hearingImpaired: Boolean, val forced: Boolean)

/** Minimal Plex Media Server client (JSON API, direct play). */
class PlexClient(private val http: OkHttpClient, private val settings: Settings, val profile: PlexProfile? = null) {

    val configured get() = settings.plexUrl.isNotBlank() && settings.plexToken.isNotBlank()

    /** The token requests go out with: the profile's, or the main account's. */
    private val authToken get() = profile?.token ?: settings.plexToken

    private fun Request.Builder.plexHeaders(token: String? = authToken): Request.Builder = apply {
        header("Accept", "application/json")
        header("X-Plex-Product", "Masal")
        header("X-Plex-Version", "0.1")
        header("X-Plex-Client-Identifier", settings.clientId)
        header("X-Plex-Platform", "Android")
        header("X-Plex-Device-Name", "Masal on ${android.os.Build.MODEL}")
        // Plex's own texts (row titles such as "Recently Added Movies") in the app's language, where the server has them.
        header("X-Plex-Language", Language.tag())
        if (!token.isNullOrBlank()) header("X-Plex-Token", token)
    }

    private fun get(path: String): JSONObject {
        val req = Request.Builder().url(settings.plexUrl + path).plexHeaders().build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401 && profile != null) throw ProfileRejectedException()
            if (!r.isSuccessful) error("Plex HTTP ${r.code}")
            return JSONObject(r.body!!.string()).getJSONObject("MediaContainer")
        }
    }

    private fun items(mc: JSONObject): List<PlexItem> {
        val arr = mc.optJSONArray("Metadata") ?: return emptyList()
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    private fun parse(o: JSONObject): PlexItem {
        val type = o.optString("type")
        fun str(k: String) = o.optString(k).ifBlank { null }
        val title: String
        val subtitle: String
        when (type) {
            "episode" -> {
                title = o.optString("grandparentTitle")
                subtitle = Language.text(R.string.common_episode_subtitle, o.optInt("parentIndex"), o.optInt("index"), o.optString("title"))
            }
            "season" -> {
                title = o.optString("parentTitle").ifBlank { o.optString("title") }
                subtitle = o.optString("title")
            }
            else -> {
                title = o.optString("title")
                subtitle = o.optInt("year").takeIf { it > 0 }?.toString() ?: ""
            }
        }
        val poster = when (type) {
            "episode" -> str("grandparentThumb") ?: str("parentThumb") ?: str("thumb")
            "season" -> str("thumb") ?: str("parentThumb")
            else -> str("thumb")
        }
        val art = when (type) {
            "episode" -> str("grandparentArt") ?: str("art")
            "season" -> str("art") ?: str("parentArt")
            else -> str("art")
        }
        val part = o.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)
        val images = o.optJSONArray("Image")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        fun tags(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("tag") } }.orEmpty()
        fun people(k: String, job: String?) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .map { CastMember(it.optString("tag"), it.optString("role").ifBlank { null }, job,
                it.optString("thumb").takeIf { t -> t.startsWith("http") }, plexFilter = it.optString("filter").ifBlank { null }) }
        val crew = listOf("Director" to "Director", "Writer" to "Writer", "Producer" to "Producer").flatMap { (k, job) -> people(k, job) }
        val tmdb = o.optJSONArray("Guid")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") } }
            ?.firstOrNull { it.startsWith("tmdb://") }?.removePrefix("tmdb://")?.toIntOrNull()
        val streams = part?.optJSONArray("Stream")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { st ->
                PlexStream(
                    id = st.optLong("id"), type = st.optInt("streamType"), codec = st.optString("codec"),
                    language = st.optString("language"), languageCode = st.optString("languageCode"),
                    title = st.optString("extendedDisplayTitle").ifBlank { st.optString("displayTitle") },
                    key = st.optString("key").ifBlank { null }, selected = st.optBoolean("selected"), forced = st.optBoolean("forced"),
                )
            }
        }.orEmpty()
        val genres = o.optJSONArray("Genre")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("tag") } }
            ?: emptyList()
        val rating = when {
            o.has("audienceRating") -> o.optDouble("audienceRating")
            o.has("rating") -> o.optDouble("rating")
            else -> null
        }?.takeIf { !it.isNaN() && it > 0 }
        return PlexItem(
            ratingKey = o.optString("ratingKey"),
            type = type,
            title = title,
            subtitle = subtitle,
            poster = poster,
            art = art,
            still = if (type == "episode") str("thumb") else null,
            viewOffset = o.optLong("viewOffset"),
            duration = o.optLong("duration"),
            partKey = part?.optString("key")?.ifBlank { null },
            summary = str("summary"),
            year = o.optInt("year").takeIf { it > 0 },
            contentRating = str("contentRating"),
            rating = rating,
            genres = genres,
            tagline = str("tagline"),
            parentRatingKey = str("parentRatingKey"),
            grandparentRatingKey = str("grandparentRatingKey"),
            index = o.optInt("index"),
            parentIndex = o.optInt("parentIndex"),
            episodeTitle = if (type == "episode") str("title") else null,
            childCount = o.optInt("childCount"),
            leafCount = o.optInt("leafCount"),
            viewedLeafCount = o.optInt("viewedLeafCount"),
            logo = images.firstOrNull { it.optString("type") == "clearLogo" }?.optString("url")?.ifBlank { null },
            released = str("originallyAvailableAt"),
            addedAt = o.optLong("addedAt"),
            lastViewedAt = o.optLong("lastViewedAt"),
            titleSort = o.optString("titleSort").ifBlank { o.optString("title") },
            cast = tags("Role").take(3),
            partId = part?.optLong("id")?.takeIf { it > 0 },
            streams = streams,
            sectionId = str("librarySectionID"),
            blur = o.optJSONObject("UltraBlurColors")?.let { b ->
                listOf("topLeft", "topRight", "bottomRight", "bottomLeft").mapNotNull { k ->
                    b.optString(k).takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
                }.takeIf { it.size == 4 }
            }.orEmpty(),
            profile = profile?.id,
            people = people("Role", null) + SeerrClient.mergeJobs(crew),
            tmdbId = tmdb,
        )
    }

    private fun hubs(mc: JSONObject): List<PlexHub> {
        val arr = mc.optJSONArray("Hub") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { h ->
            val id = h.optString("hubIdentifier")
            PlexHub(id, PlexNames.hub(id, h.optString("title")), items(h))
        }.filter { it.items.isNotEmpty() }
    }

    fun onDeck() = items(get("/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=30"))
    fun recentlyAdded() = items(get("/library/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=40"))
    fun sectionItems(key: String) = items(get("/library/sections/$key/all"))
    fun children(ratingKey: String) = items(get("/library/metadata/$ratingKey/children"))
    @Volatile private var serverIdCache: String? = null

    /** The server's machine identifier, which the Plex app's links name the server by. */
    fun serverId(): String = serverIdCache ?: get("/identity").getString("machineIdentifier").also { serverIdCache = it }

    fun metadata(ratingKey: String) = items(get("/library/metadata/$ratingKey")).first()

    /** Plex's own home rows (Continue Watching, Recently Added, promoted collections). */
    fun homeHubs() = hubs(get("/hubs?count=20"))
    /** Plex's search across all libraries: one hub per kind (movies, shows, episodes), titles only. */
    fun search(query: String): List<PlexHub> =
        hubs(get("/hubs/search?query=" + java.net.URLEncoder.encode(query, "UTF-8") + "&limit=20"))
            .map { h -> h.copy(items = h.items.filter { it.type in SearchTypes }) }
            .filter { it.items.isNotEmpty() }

    /** Recommended rows of one library. */
    fun sectionHubs(key: String) = hubs(get("/hubs/sections/$key?count=20"))
    fun collections(sectionKey: String) = items(get("/library/sections/$sectionKey/collections"))
    fun collectionItems(ratingKey: String) = items(get("/library/collections/$ratingKey/children"))
    /** Genres of a library as (id, name). */
    fun genres(sectionKey: String): List<Pair<String, String>> {
        val arr = get("/library/sections/$sectionKey/genre").optJSONArray("Directory") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.optString("key") to it.optString("title") }
    }
    fun genreItems(sectionKey: String, genreId: String) = items(get("/library/sections/$sectionKey/all?genre=$genreId"))

    /** A person's titles in the film and series libraries, by Plex's filter ("actor=123"), newest first. */
    fun personTitles(filter: String): List<PlexItem> = sections().flatMap { s ->
        runCatching { items(get("/library/sections/${s.key}/all?$filter")) }.getOrDefault(emptyList())
    }.sortedByDescending { it.released ?: it.year?.toString() ?: "" }

    // ---- subtitles ----

    /** Searches subtitles online (OpenSubtitles through Plex), like "Search for subtitles" in Plex. */
    fun searchSubtitles(ratingKey: String, language: String): List<SubtitleResult> {
        val arr = get("/library/metadata/$ratingKey/subtitles?language=$language").optJSONArray("Stream") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            SubtitleResult(it.optString("key"), it.optString("codec"), it.optString("language"),
                it.optString("title").ifBlank { it.optString("displayTitle") }, it.optString("providerTitle"),
                it.optDouble("score", 0.0), it.optBoolean("hearingImpaired"), it.optBoolean("forced"))
        }.sortedWith(compareBy<SubtitleResult> { it.forced }.thenByDescending { it.score }) // forced ones only cover foreign lines
    }

    /** Asks Plex to download a found subtitle; it appears as a new stream of the item shortly after. */
    fun downloadSubtitle(ratingKey: String, r: SubtitleResult) {
        // Same parameters as Plex Web's "Search for subtitles".
        val q = "/library/metadata/$ratingKey/subtitles?key=${Uri.encode(r.key)}&codec=${Uri.encode(r.codec)}" +
            "&language=${Uri.encode(r.language)}&hearingImpaired=${if (r.hearingImpaired) 1 else 0}&forced=${if (r.forced) 1 else 0}" +
            "&providerTitle=${Uri.encode(r.provider)}"
        val req = Request.Builder().url(settings.plexUrl + q).put(ByteArray(0).toRequestBody()).plexHeaders().build()
        http.newCall(req).execute().use { if (!it.isSuccessful) error("Plex HTTP ${it.code}") }
    }

    /** Remembers an audio or subtitle choice in Plex (subtitle 0 = off), so other Plex apps start with it too. */
    fun selectStream(partId: Long, param: String, streamId: Long) {
        runCatching {
            val req = Request.Builder().url(settings.plexUrl + "/library/parts/$partId?$param=$streamId&allParts=1")
                .put(ByteArray(0).toRequestBody()).plexHeaders().build()
            http.newCall(req).execute().close()
        }
    }

    /** The next episode Plex suggests for a show ("On Deck"), if any. */
    fun showOnDeck(ratingKey: String): PlexItem? {
        val m = get("/library/metadata/$ratingKey?includeOnDeck=1").optJSONArray("Metadata")?.optJSONObject(0)
        val deck = m?.optJSONObject("OnDeck")?.optJSONArray("Metadata")?.optJSONObject(0) ?: return null
        return parse(deck)
    }

    fun sections(): List<PlexSection> {
        val arr = get("/library/sections").optJSONArray("Directory") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("type") == "movie" || it.optString("type") == "show" }
            .map { PlexSection(it.optString("key"), PlexNames.section(it.optString("title")), it.optString("type"), it.optString("art").ifBlank { null }) }
    }

    fun image(path: String?, w: Int, h: Int): String? {
        if (path == null || !configured) return null
        return "${settings.plexUrl}/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1" +
            "&url=${Uri.encode(path)}&X-Plex-Token=${settings.plexToken}"
    }

    /** Title artwork keeps its transparency only as PNG. */
    fun logoImage(path: String?, w: Int = 640, h: Int = 260): String? =
        image(path, w, h)?.let { "$it&format=png" }

    fun streamUrl(partKey: String) =
        if (partKey.startsWith("http")) partKey
        else settings.plexUrl + partKey + (if ('?' in partKey) "&" else "?") + "X-Plex-Token=" + authToken

    private val trailers = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private object NoTrailer

    /** First trailer among the item's extras (local or from Plex's own metadata), cached per session. */
    fun trailer(ratingKey: String): PlexItem? {
        trailers[ratingKey]?.let { return it as? PlexItem }
        val found = runCatching {
            val arr = get("/library/metadata/$ratingKey/extras").optJSONArray("Metadata")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            val o = list.firstOrNull { it.optInt("extraType") == 1 || it.optString("subtype") == "trailer" }
            o?.let { parse(it) }?.let { t -> if (t.partKey != null) t else runCatching { metadata(t.ratingKey) }.getOrNull() }
        }.onFailure { android.util.Log.w("MasalBg", "extras $ratingKey: ${it.message}") }.getOrNull()
        android.util.Log.i("MasalBg", "trailer $ratingKey: ${found?.partKey?.substringBefore('?') ?: "none"}")
        trailers[ratingKey] = found?.takeIf { it.partKey != null } ?: NoTrailer
        return found?.takeIf { it.partKey != null }
    }

    /** Plex's metadata service (Plex's online catalogue, also for titles not in the library); tests point it elsewhere. */
    @Volatile internal var metadataBase = "https://metadata.provider.plex.tv"
    private val onlineTrailers = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The address of Plex's online trailer for a TMDB title (seerr.md), also when it is not in the
     * library: Plex's catalogue entry by `tmdb://<id>`, then its first trailer extra. Asked with the
     * account's token; null without one. Found and "none" are kept for the session, failures not.
     * [language] (e.g. "en") is asked for as `X-Plex-Language`: Plex hands out the trailer dubbed in
     * the language the client asks for, where it has one; Masal passes Seerr's language (seerr.md).
     */
    fun onlineTrailer(tmdbId: Int, mediaType: String, language: String = Language.tag()): String? {
        val token = settings.plexToken.takeIf { it.isNotBlank() } ?: return null
        val cacheKey = "$mediaType:$tmdbId:$language"
        onlineTrailers[cacheKey]?.let { return it.ifEmpty { null } }
        fun json(url: String): JSONObject {
            val req = Request.Builder().url(url).plexHeaders(token).header("X-Plex-Language", language).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("Plex metadata HTTP ${r.code}")
                return JSONObject(r.body!!.string()).getJSONObject("MediaContainer")
            }
        }
        val found = runCatching {
            val type = if (mediaType == "tv") 2 else 1
            val match = json("$metadataBase/library/metadata/matches?type=$type&guid=" + Uri.encode("tmdb://$tmdbId"))
                .optJSONArray("Metadata")?.optJSONObject(0)?.optString("ratingKey")?.takeIf { it.isNotBlank() }
                ?: return@runCatching ""
            val arr = json("$metadataBase/library/metadata/${Uri.encode(match)}/extras").optJSONArray("Metadata") ?: return@runCatching ""
            val extras = (0 until arr.length()).map { arr.getJSONObject(it) }
            val trailer = extras.firstOrNull { it.optString("subtype") == "trailer" || it.optInt("extraType") == 1 } ?: return@runCatching ""
            val part = trailer.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)
                ?.optString("key")?.takeIf { it.isNotBlank() } ?: return@runCatching ""
            if (part.startsWith("http")) part else metadataBase + part + (if ('?' in part) "&" else "?") + "X-Plex-Token=" + token
        }.onFailure { android.util.Log.w("MasalBg", "online trailer $cacheKey: ${it.message}") }.getOrNull() ?: return null
        android.util.Log.i("MasalBg", "online trailer $cacheKey: ${if (found.isEmpty()) "none" else "found"}")
        onlineTrailers[cacheKey] = found
        return found.ifEmpty { null }
    }

    /** Reports playback progress so Plex keeps "continue watching" in sync. */
    fun timeline(item: PlexItem, state: String, timeMs: Long, durationMs: Long) {
        val q = "/:/timeline?ratingKey=${item.ratingKey}&key=${Uri.encode("/library/metadata/${item.ratingKey}")}" +
            "&state=$state&time=$timeMs&duration=$durationMs"
        runCatching {
            http.newCall(Request.Builder().url(settings.plexUrl + q).plexHeaders().build()).execute().close()
        }
    }

    fun scrobble(ratingKey: String) {
        runCatching {
            val q = "/:/scrobble?identifier=com.plexapp.plugins.library&key=$ratingKey"
            http.newCall(Request.Builder().url(settings.plexUrl + q).plexHeaders().build()).execute().close()
        }
    }

    // ---- plex.tv account link (PIN flow, user enters the code at plex.tv/link) ----

    /** plex.tv; the UI tests point it at a closed port so they never reach the internet. */
    @Volatile internal var accountBase = "https://plex.tv"

    data class Pin(val id: Long, val code: String)

    fun createPin(): Pin {
        val req = Request.Builder().url("$accountBase/api/v2/pins")
            .post(FormBody.Builder().add("strong", "false").build())
            .plexHeaders(token = null).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("plex.tv HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            return Pin(o.getLong("id"), o.getString("code"))
        }
    }

    /** Returns the auth token once the code was entered, null while still pending. */
    fun checkPin(pin: Pin): String? {
        val req = Request.Builder().url("$accountBase/api/v2/pins/${pin.id}").plexHeaders(token = null).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("plex.tv HTTP ${r.code}")
            return JSONObject(r.body!!.string()).optString("authToken").takeIf { it.isNotBlank() && it != "null" }
        }
    }

    // ---- Plex Home members (profiles.md) ----

    /** The linked account's display name. */
    fun accountName(): String? {
        val req = Request.Builder().url("$accountBase/api/v2/user").plexHeaders(settings.plexToken).build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JSONObject(r.body!!.string())
            o.optString("friendlyName").ifBlank { o.optString("title") }.ifBlank { o.optString("username") }.ifBlank { null }
        }
    }

    /** Members of the main account's Plex Home, the main account itself left out. */
    fun homeUsers(): List<PlexHomeUser> {
        val req = Request.Builder().url("$accountBase/api/v2/home/users").plexHeaders(settings.plexToken).build()
        val users = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("plex.tv HTTP ${r.code}")
            JSONObject(r.body!!.string()).optJSONArray("users") ?: JSONArray()
        }
        return (0 until users.length()).map { users.getJSONObject(it) }
            .filter { !it.optBoolean("admin") && it.optString("uuid").isNotBlank() }
            .map { PlexHomeUser(it.optString("uuid"), it.optString("title").ifBlank { it.optString("username") }, it.optBoolean("protected")) }
    }

    /**
     * Signs in as a Home member (with its PIN where it has one, used once and never stored) and
     * returns the member's token for this server. Throws [WrongPinException] for a refused PIN.
     */
    fun profileToken(userId: String, pin: String?): String {
        val url = "$accountBase/api/v2/home/users/${Uri.encode(userId)}/switch" +
            (if (pin.isNullOrBlank()) "" else "?pin=${Uri.encode(pin)}")
        val req = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).plexHeaders(settings.plexToken).build()
        val userToken = http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw WrongPinException()
            if (!r.isSuccessful) error("plex.tv HTTP ${r.code}")
            val o = JSONObject(r.body!!.string())
            o.optString("authToken").ifBlank { o.optString("authenticationToken") }.takeIf { it.isNotBlank() && it != "null" }
                ?: error("plex.tv: no token")
        }
        return serverToken(userToken) ?: userToken
    }

    /** The token a member's account has for this server (servers hand out their own access tokens). */
    private fun serverToken(userToken: String): String? {
        val req = Request.Builder().url("$accountBase/api/v2/resources?includeHttps=1&includeRelay=0").plexHeaders(userToken).build()
        val arr = http.newCall(req).execute().use { r -> if (!r.isSuccessful) return null; JSONArray(r.body!!.string()) }
        val servers = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("provides").contains("server") }
        val host = Uri.parse(settings.plexUrl).host.orEmpty()
        fun matches(s: JSONObject): Boolean {
            val cons = s.optJSONArray("connections") ?: return false
            return (0 until cons.length()).map { cons.getJSONObject(it) }.any { c ->
                c.optString("address") == host || Uri.parse(c.optString("uri")).host == host
            }
        }
        val server = servers.firstOrNull(::matches) ?: servers.singleOrNull()
        return server?.optString("accessToken")?.takeIf { it.isNotBlank() && it != "null" }
    }

    /** Finds a local address of the user's own Plex server. */
    fun findServerUrl(token: String): String? {
        val req = Request.Builder().url("$accountBase/api/v2/resources?includeHttps=1&includeRelay=0")
            .plexHeaders(token).build()
        val arr = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONArray(r.body!!.string())
        }
        val servers = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("provides").contains("server") }
            .sortedByDescending { it.optBoolean("owned") }
        for (s in servers) {
            val cons = s.optJSONArray("connections") ?: continue
            val list = (0 until cons.length()).map { cons.getJSONObject(it) }
            val local = list.filter { it.optBoolean("local") && !it.optBoolean("relay") }
                .sortedBy { it.optString("address").startsWith("172.") }
                .firstOrNull()
            if (local != null) return "http://${local.optString("address")}:${local.optInt("port", 32400)}"
            list.firstOrNull()?.optString("uri")?.let { return it }
        }
        return null
    }
}
