package com.alike.masal.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * A person in a title's cast or crew (people.md). [role] is the character, [job] the crew job
 * (null for actors). [tmdbId] comes from Seerr; [plexFilter] ("actor=123") from Plex, used
 * when Seerr is not set up.
 */
data class CastMember(
    val name: String,
    val role: String?,
    val job: String?,
    val photo: String?,
    val tmdbId: Int? = null,
    val plexFilter: String? = null,
)

/** Seerr's view of a title: not known, requested, being processed, in Plex (also partly). */
enum class SeerrStatus { None, Requested, Processing, Available }

/** Seerr's media status number (1 unknown, 2 pending, 3 processing, 4 partly available, 5 available, 6 deleted). */
fun seerrStatus(code: Int): SeerrStatus = when (code) {
    2 -> SeerrStatus.Requested
    3 -> SeerrStatus.Processing
    4, 5 -> SeerrStatus.Available
    else -> SeerrStatus.None
}

/** A film or series from Seerr (search, filmography). [jobs] are the person's roles and jobs in it. */
data class SeerrTitle(
    val tmdbId: Int,
    val mediaType: String, // movie, tv
    val title: String,
    val year: Int?,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val status: SeerrStatus,
    /** Plex's ratingKey when the title is in Plex. */
    val ratingKey: String?,
    val jobs: List<String> = emptyList(),
    /** Sort key: the release date (yyyy-MM-dd), empty when unknown. */
    val date: String = "",
)

data class SeerrSeason(val number: Int, val name: String, val episodes: Int, val status: SeerrStatus)

/** A title's page from Seerr: [title] plus details, cast and (for series) seasons. */
data class SeerrDetails(
    val title: SeerrTitle,
    val runtime: Int?,
    val genres: List<String>,
    val cast: List<CastMember>,
    val seasons: List<SeerrSeason>,
    val requestedAt: String?,
)

data class SeerrPerson(
    val tmdbId: Int,
    val name: String,
    val photo: String?,
    val knownFor: String?,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val biography: String?,
)

data class SeerrUser(val id: Int, val name: String, val plexUsername: String?)

/** Search results: titles not in Plex (in Plex they come from Plex's own search) and people. */
data class SeerrSearch(val titles: List<SeerrTitle>, val people: List<CastMember>)

/**
 * One of Seerr's Discover sliders that holds titles (seerr.md, "Discover rows on Home"). [type] is
 * Seerr's `DiscoverSliderType`; built-in sliders have no [title] of their own.
 */
data class SeerrSlider(val id: Int, val type: Int, val title: String?, val data: String?) {
    companion object {
        const val Trending = 4
        const val PopularMovies = 5
        const val UpcomingMovies = 7
        const val PopularTv = 9
        const val UpcomingTv = 11
        const val MovieKeyword = 13
        const val MovieGenre = 14
        const val TvKeyword = 15
        const val TvGenre = 16
        const val Search = 17
        const val Studio = 18
        const val Network = 19
        const val MovieStreaming = 20
        const val TvStreaming = 21
        /** The types Masal can show as a row of titles. */
        val TitleTypes = setOf(Trending, PopularMovies, UpcomingMovies, PopularTv, UpcomingTv, MovieKeyword, MovieGenre,
            TvKeyword, TvGenre, Search, Studio, Network, MovieStreaming, TvStreaming)
    }

    /** Home row id; also the key in `home_rows_on` and `home_order`. */
    val rowId get() = "seerr:$id"

    /** The list behind the slider, as Seerr's own Discover page loads it (without page and language). */
    fun path(today: String): String? {
        fun enc(v: String?) = URLEncoder.encode(v.orEmpty(), "UTF-8").replace("+", "%20")
        val d = data.orEmpty()
        val region = enc(d.substringBefore(',')); val providers = enc(d.substringAfter(',', ""))
        return when (type) {
            Trending -> "/discover/trending?"
            PopularMovies -> "/discover/movies?"
            PopularTv -> "/discover/tv?"
            UpcomingMovies -> "/discover/movies?primaryReleaseDateGte=$today&"
            UpcomingTv -> "/discover/tv?firstAirDateGte=$today&"
            MovieKeyword -> "/discover/movies?keywords=${enc(d)}&"
            TvKeyword -> "/discover/tv?keywords=${enc(d)}&"
            MovieGenre -> "/discover/movies?genre=${enc(d)}&"
            TvGenre -> "/discover/tv?genre=${enc(d)}&"
            Studio -> "/discover/movies/studio/${enc(d)}?"
            Network -> "/discover/tv/network/${enc(d)}?"
            Search -> "/search?query=${enc(d)}&"
            MovieStreaming -> "/discover/movies?watchRegion=$region&watchProviders=$providers&"
            TvStreaming -> "/discover/tv?watchRegion=$region&watchProviders=$providers&"
            else -> null
        }
    }
}

/** A failed Seerr call; [message] is Seerr's own text where it gave one (e.g. quota reached). */
class SeerrException(message: String, val code: Int = 0) : Exception(message)

private const val TmdbImages = "https://image.tmdb.org/t/p/"

/** How long a Discover row is kept before Home loads it again (seerr.md). */
const val DiscoverMaxAge = 12L * 3600_000

fun tmdbImage(path: String?, size: String): String? = path?.takeIf { it.startsWith("/") }?.let { TmdbImages + size + it }

/**
 * Seerr client (seerr.md): `/api/v1` with the API key. Person pages and title details are
 * cached in [cache] for a week; statuses are fetched again whenever a page opens.
 */
class SeerrClient(private val http: OkHttpClient, private val settings: Settings, val cache: SeerrCache) {

    val configured get() = settings.seerrUrl.isNotBlank() && settings.seerrKey.isNotBlank()

    private fun url(path: String) = settings.seerrUrl + "/api/v1" + path

    private fun call(req: Request.Builder): String {
        val r = http.newCall(req.header("X-Api-Key", settings.seerrKey).header("Accept", "application/json").build()).execute()
        r.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val msg = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                throw SeerrException(msg.ifBlank { "Seerr HTTP ${it.code}" }, it.code)
            }
            return body
        }
    }

    private fun get(path: String): JSONObject = JSONObject(call(Request.Builder().url(url(path))))

    private fun lang() = "language=" + Language.tag().substringBefore('-').ifBlank { "en" }

    /** The Seerr user the key belongs to; checks address and key. */
    fun me(url: String = settings.seerrUrl, key: String = settings.seerrKey): SeerrUser {
        val r = http.newCall(Request.Builder().url(url.trim().trimEnd('/') + "/api/v1/auth/me")
            .header("X-Api-Key", key.trim()).header("Accept", "application/json").build()).execute()
        r.use {
            if (it.code == 401 || it.code == 403) throw SeerrException("key refused", it.code)
            if (!it.isSuccessful) throw SeerrException("Seerr HTTP ${it.code}", it.code)
            return user(JSONObject(it.body!!.string()))
        }
    }

    private fun user(o: JSONObject) = SeerrUser(
        o.optInt("id"),
        o.optString("displayName").ifBlank { o.optString("username").ifBlank { o.optString("plexUsername") } },
        o.optString("plexUsername").ifBlank { null },
    )

    fun users(): List<SeerrUser> {
        val arr = get("/user?take=200&sort=displayname").optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { user(arr.getJSONObject(it)) }
    }

    // ---- titles ----

    private fun title(o: JSONObject, jobs: List<String> = emptyList()): SeerrTitle {
        val type = o.optString("mediaType").ifBlank { if (o.has("firstAirDate")) "tv" else "movie" }
        val date = (if (type == "tv") o.optString("firstAirDate") else o.optString("releaseDate")).takeIf { it != "null" }.orEmpty()
        val info = o.optJSONObject("mediaInfo")
        return SeerrTitle(
            tmdbId = o.optInt("id"),
            mediaType = type,
            title = (if (type == "tv") o.optString("name") else o.optString("title")).ifBlank { o.optString("originalTitle") },
            year = date.take(4).toIntOrNull(),
            poster = tmdbImage(o.optString("posterPath"), "w342"),
            backdrop = tmdbImage(o.optString("backdropPath"), "w1280"),
            overview = o.optString("overview").ifBlank { null },
            status = seerrStatus(info?.optInt("status") ?: 0),
            ratingKey = info?.optString("ratingKey")?.takeIf { it.isNotBlank() && it != "null" },
            jobs = jobs,
            date = date,
        )
    }

    fun search(query: String): SeerrSearch {
        val arr = get("/search?query=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20") + "&page=1&" + lang())
            .optJSONArray("results") ?: JSONArray()
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }
        val titles = all.filter { it.optString("mediaType") == "movie" || it.optString("mediaType") == "tv" }.map { title(it) }
        val people = all.filter { it.optString("mediaType") == "person" }.map {
            CastMember(it.optString("name"), null, null, tmdbImage(it.optString("profilePath"), "w185"), tmdbId = it.optInt("id"))
        }
        return SeerrSearch(titles, people)
    }

    // ---- Discover rows on Home ----

    /** Seerr's Discover sliders that hold titles, in Seerr's order (also those switched off in Seerr). */
    fun sliders(): List<SeerrSlider> = parseSliders(call(Request.Builder().url(url("/settings/discover"))))

    internal fun parseSliders(raw: String): List<SeerrSlider> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .sortedBy { it.optInt("order", Int.MAX_VALUE) }
            .map { o ->
                fun s(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
                SeerrSlider(o.optInt("id"), o.optInt("type"), s("title"), s("data"))
            }
            .filter { it.type in SeerrSlider.TitleTypes }
    }

    private fun discoverKey(slider: SeerrSlider) = "discover-${slider.id}-${slider.type}-${slider.data.orEmpty().hashCode()}-${Language.tag()}"

    /** A slider's first page from the cache when younger than [maxAgeMs] (12 hours). */
    fun cachedDiscover(slider: SeerrSlider, maxAgeMs: Long = DiscoverMaxAge): List<SeerrTitle>? =
        cache.get(discoverKey(slider), maxAgeMs = maxAgeMs)?.let { runCatching { parseResults(it) }.getOrNull() }

    /** A slider's first page (about 20 titles) as Seerr has it now; stored for 12 hours. */
    fun discover(slider: SeerrSlider, today: String): List<SeerrTitle> {
        val path = slider.path(today) ?: return emptyList()
        val raw = call(Request.Builder().url(url(path + "page=1&" + lang())))
        cache.put(discoverKey(slider), raw)
        return parseResults(raw)
    }

    /** Films and series of a list response; people (trending, search) are left out. */
    internal fun parseResults(raw: String): List<SeerrTitle> {
        val arr = JSONObject(raw).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("mediaType").let { t -> t.isBlank() || t == "movie" || t == "tv" } }
            .map { title(it) }
            .filter { it.title.isNotBlank() }
            .distinctBy { it.mediaType + it.tmdbId }
    }

    private fun castOf(o: JSONObject): List<CastMember> {
        val credits = o.optJSONObject("credits") ?: return emptyList()
        val cast = credits.optJSONArray("cast")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .sortedBy { it.optInt("order", 999) }.take(20)
            .map { CastMember(it.optString("name"), it.optString("character").ifBlank { null }, null,
                tmdbImage(it.optString("profilePath"), "w185"), tmdbId = it.optInt("id")) }
        val crew = credits.optJSONArray("crew")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .filter { it.optString("job") in CrewJobs }
            .sortedBy { CrewJobs.indexOf(it.optString("job")) }
            .map { CastMember(it.optString("name"), null, it.optString("job"), tmdbImage(it.optString("profilePath"), "w185"), tmdbId = it.optInt("id")) }
        return cast + mergeJobs(crew)
    }

    private fun titleKey(mediaType: String, tmdbId: Int) = "title-$mediaType-$tmdbId-${Language.tag()}"

    /** A title's page from the cache, or null when there is none younger than a week. */
    fun cachedDetails(mediaType: String, tmdbId: Int): SeerrDetails? =
        cache.get(titleKey(mediaType, tmdbId))?.let { runCatching { parseDetails(mediaType, it) }.getOrNull() }

    /** A title's page as it is now (status included); stored for the next visit. */
    fun details(mediaType: String, tmdbId: Int): SeerrDetails {
        val raw = call(Request.Builder().url(url("/$mediaType/$tmdbId?" + lang())))
        cache.put(titleKey(mediaType, tmdbId), raw)
        return parseDetails(mediaType, raw)
    }

    internal fun parseDetails(mediaType: String, raw: String): SeerrDetails {
        val o = JSONObject(raw)
        o.put("mediaType", mediaType)
        val t = title(o)
        val info = o.optJSONObject("mediaInfo")
        val seasonStatus = info?.optJSONArray("seasons")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.associate { it.optInt("seasonNumber") to seerrStatus(it.optInt("status")) }
        }.orEmpty()
        // Seasons asked for in open requests count as requested even before Seerr updates their status.
        val requested = info?.optJSONArray("requests")?.let { a ->
            (0 until a.length()).flatMap { i ->
                a.getJSONObject(i).optJSONArray("seasons")?.let { s -> (0 until s.length()).map { s.getJSONObject(it).optInt("seasonNumber") } }.orEmpty()
            }
        }.orEmpty().toSet()
        val seasons = o.optJSONArray("seasons")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
            .filter { it.optInt("seasonNumber") > 0 }
            .map {
                val n = it.optInt("seasonNumber")
                val s = seasonStatus[n] ?: SeerrStatus.None
                SeerrSeason(n, it.optString("name"), it.optInt("episodeCount"),
                    if (s == SeerrStatus.None && n in requested) SeerrStatus.Requested else s)
            }
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
        val runtime = (if (mediaType == "tv") o.optJSONArray("episodeRunTime")?.optInt(0) else o.optInt("runtime"))?.takeIf { it > 0 }
        val requestedAt = info?.optJSONArray("requests")?.optJSONObject(0)?.optString("createdAt")?.take(10)?.ifBlank { null }
        return SeerrDetails(t, runtime, genres, castOf(o), seasons, requestedAt)
    }

    // ---- people ----

    fun person(tmdbId: Int): SeerrPerson {
        val key = "person-$tmdbId-${Language.tag()}"
        val raw = cache.get(key) ?: call(Request.Builder().url(url("/person/$tmdbId?" + lang()))).also { cache.put(key, it) }
        val o = JSONObject(raw)
        fun s(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
        return SeerrPerson(tmdbId, o.optString("name"), tmdbImage(o.optString("profilePath"), "h632"),
            s("knownForDepartment"), s("birthday"), s("deathday"), s("placeOfBirth"), s("biography"))
    }

    /** Cached filmography, or null when there is none younger than a week. */
    fun cachedCredits(tmdbId: Int): List<SeerrTitle>? = cache.get(creditsKey(tmdbId))?.let { parseCredits(it) }

    /** The filmography as it is now (statuses included); stored for the next visit. */
    fun credits(tmdbId: Int): List<SeerrTitle> {
        val raw = call(Request.Builder().url(url("/person/$tmdbId/combined_credits?" + lang())))
        cache.put(creditsKey(tmdbId), raw)
        return parseCredits(raw)
    }

    private fun creditsKey(tmdbId: Int) = "credits-$tmdbId-${Language.tag()}"

    /** One entry per title, newest first; an actor who also directed it gets both in [SeerrTitle.jobs]. */
    internal fun parseCredits(raw: String): List<SeerrTitle> {
        val o = JSONObject(raw)
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()
        val entries = list("cast").map { it to (it.optString("character").ifBlank { null }?.let { c -> "as:$c" } ?: "as:") } +
            list("crew").map { it to "job:" + it.optString("job") }
        return entries
            .filter { (it, _) -> it.optString("mediaType") == "movie" || it.optString("mediaType") == "tv" }
            .groupBy { (it, _) -> it.optString("mediaType") + ":" + it.optInt("id") }
            .map { (_, group) -> title(group.first().first, group.map { it.second }.distinct()) }
            // Talk shows and award ceremonies list hundreds of guest appearances: those need a poster to count.
            .filter { it.poster != null || it.status == SeerrStatus.Available }
            .sortedWith(compareByDescending<SeerrTitle> { it.date.ifBlank { "9999" } }.thenBy { it.title })
    }

    // ---- requests ----

    /** Files a request as Seerr user [userId] (null: the key's own user); [seasons] only for series. */
    fun request(mediaType: String, tmdbId: Int, seasons: List<Int>, userId: Int?) {
        val body = JSONObject().put("mediaType", mediaType).put("mediaId", tmdbId)
        if (mediaType == "tv") body.put("seasons", JSONArray(seasons))
        val req = Request.Builder().url(url("/request"))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (userId != null) req.header("X-API-User", userId.toString())
        call(req)
        // The cached title page shows the old status.
        cache.remove(titleKey(mediaType, tmdbId))
        requested.value = requested.value + "$mediaType:$tmdbId"
    }

    /** Titles requested from Masal in this session ("movie:<tmdbId>"): Home's Seerr rows mark them at once. */
    val requested = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

    /**
     * The Seerr user a profile requests as ("" or null = the main account): the one chosen in
     * Settings, else the Seerr user with the profile's Plex name, else null (the key's own user).
     */
    fun userFor(profileId: String?): Int? {
        val key = profileId.orEmpty()
        settings.seerrUsers[key]?.let { return it.first }
        return matchUser(key, runCatching { users() }.getOrDefault(emptyList()))?.id
    }

    /** The Seerr user whose name or Plex user name is the profile's Plex name. */
    fun matchUser(profileKey: String, users: List<SeerrUser>): SeerrUser? {
        val name = if (profileKey.isEmpty()) settings.plexAccountName else settings.profiles.firstOrNull { it.id == profileKey }?.name
        if (name.isNullOrBlank()) return null
        return users.firstOrNull { it.name.equals(name, true) || it.plexUsername.equals(name, true) }
    }

    companion object {
        /** Crew jobs shown with the cast, in this order. */
        val CrewJobs = listOf("Director", "Screenplay", "Writer", "Creator", "Producer")

        /** One entry per person, their jobs joined ("Director, Writer"). */
        fun mergeJobs(crew: List<CastMember>): List<CastMember> =
            crew.groupBy { it.tmdbId ?: it.name }.map { (_, g) -> g.first().copy(job = g.mapNotNull { it.job }.distinct().joinToString(", ")) }
    }
}

/**
 * Seerr responses on the device for [maxAgeMs] (a week), at most [maxEntries] files; the
 * oldest go first. Plain files in the app's cache folder.
 */
class SeerrCache(private val dir: File, private val maxAgeMs: Long = 7L * 24 * 3600_000, private val maxEntries: Int = 300) {
    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    fun get(key: String, now: Long = System.currentTimeMillis(), maxAgeMs: Long = this.maxAgeMs): String? {
        val f = file(key)
        if (!f.exists() || now - f.lastModified() > maxAgeMs) return null
        return runCatching { f.readText() }.getOrNull()
    }

    fun put(key: String, value: String) {
        runCatching {
            dir.mkdirs()
            file(key).writeText(value)
            trim()
        }
    }

    fun remove(key: String) { file(key).delete() }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    /** Keeps the newest [maxEntries] people (person pages count once: details and filmography). */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.name.endsWith(".json") } ?: return
        if (files.size <= maxEntries * 2) return
        files.sortedBy { it.lastModified() }.take(files.size - maxEntries * 2).forEach { it.delete() }
    }
}
