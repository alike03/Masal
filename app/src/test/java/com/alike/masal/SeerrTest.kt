package com.alike.masal

import androidx.test.core.app.ApplicationProvider
import com.alike.masal.data.CastMember
import com.alike.masal.data.Language
import com.alike.masal.data.PlexClient
import com.alike.masal.data.PlexProfile
import com.alike.masal.data.SeerrCache
import com.alike.masal.data.SeerrClient
import com.alike.masal.data.SeerrException
import com.alike.masal.data.SeerrStatus
import com.alike.masal.data.Settings
import com.alike.masal.ui.stripCast
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Seerr (seerr.md, people.md) against a mock Seerr. Fictitious data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeerrTest {
    private val server = MockWebServer()
    private lateinit var settings: Settings
    private lateinit var seerr: SeerrClient
    private lateinit var cacheDir: File
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun init() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        Language.init(context)
        settings = Settings(context)
        server.dispatcher = MockSeerr.dispatcher(requests)
        server.start()
        settings.seerrUrl = server.url("/").toString()
        settings.seerrKey = "test-key"
        settings.plexAccountName = "MEMO Tester"
        cacheDir = File(context.cacheDir, "seerr-test").also { it.deleteRecursively() }
        seerr = SeerrClient(OkHttpClient(), settings, SeerrCache(cacheDir))
    }

    @After
    fun stop() { server.shutdown(); settings.clearSeerr(); cacheDir.deleteRecursively() }

    @Test
    fun keyIsChecked() {
        assertEquals("MEMO Tester", seerr.me().name)
        assertEquals("test-key", requests.last().getHeader("X-Api-Key"))
        try { seerr.me(settings.seerrUrl, "wrong"); fail("wrong key accepted") } catch (e: SeerrException) { assertEquals(401, e.code) }
    }

    @Test
    fun searchSplitsTitlesAndPeople() {
        val r = seerr.search("test")
        assertEquals(listOf("Testfilm", "Testserie", "Zweiter Testfilm"), r.titles.map { it.title })
        assertEquals(listOf(SeerrStatus.Available, SeerrStatus.None, SeerrStatus.Requested), r.titles.map { it.status })
        assertEquals("tv", r.titles[1].mediaType)
        assertEquals(listOf("Tess Tester"), r.people.map { it.name })
        assertEquals(501, r.people.single().tmdbId)
        assertTrue(r.titles[0].poster!!.startsWith("https://image.tmdb.org/t/p/"))
        assertTrue(requests.last().path!!.contains("query=test"))
    }

    @Test
    fun filmographyOneEntryPerTitleNewestFirst() {
        val credits = seerr.credits(501)
        assertEquals(listOf("Zweiter Testfilm", "Testserie", "Testfilm"), credits.map { it.title })
        // Acted in and directed: one entry with both.
        assertEquals(listOf("as:Rick", "job:Director"), credits.first { it.title == "Testfilm" }.jobs)
        assertEquals("7", credits.first { it.title == "Testfilm" }.ratingKey)
        assertEquals(SeerrStatus.Available, credits.first { it.title == "Testfilm" }.status)
        // A talk show appearance without poster is left out.
        assertFalse(credits.any { it.title == "Late Test Show" })
        // Second visit: from the cache, no request.
        val before = requests.size
        assertEquals(credits, seerr.cachedCredits(501))
        assertEquals(before, requests.size)
    }

    @Test
    fun titleDetailsWithSeasonsAndCast() {
        val d = seerr.details("tv", 300)
        assertEquals(listOf(SeerrStatus.Available, SeerrStatus.Requested, SeerrStatus.None), d.seasons.map { it.status })
        assertEquals(listOf("Tess Tester", "Theo Tester", "Dana Director"), d.cast.map { it.name })
        assertEquals("Director, Producer", d.cast.last().job)
        assertEquals("Rick", d.cast.first().role)
        assertEquals(d, seerr.cachedDetails("tv", 300))
    }

    @Test
    fun requestsGoOutAsTheProfilesUser() {
        settings.saveProfile(PlexProfile("kid-2", "Tester Kind", "t"))
        // Matched by name: the profile "Tester Kind" is Seerr user 12.
        seerr.request("tv", 300, listOf(3), seerr.userFor("kid-2"))
        val tv = requests.last { it.path == "/api/v1/request" }
        assertEquals("12", tv.getHeader("X-API-User"))
        val body = JSONObject(tv.body.readUtf8())
        assertEquals("tv", body.getString("mediaType"))
        assertEquals(300, body.getInt("mediaId"))
        assertEquals(3, body.getJSONArray("seasons").getInt(0))

        // Chosen in Settings wins over the name.
        settings.seerrUsers = mapOf("kid-2" to (11 to "Other"))
        seerr.request("movie", 101, emptyList(), seerr.userFor("kid-2"))
        val movie = requests.last { it.path == "/api/v1/request" }
        assertEquals("11", movie.getHeader("X-API-User"))
        assertFalse(JSONObject(movie.body.readUtf8()).has("seasons"))

        // Main account without a match: the key's own user (no header).
        settings.plexAccountName = "Nobody"
        seerr.request("movie", 101, emptyList(), seerr.userFor(null))
        assertNull(requests.last { it.path == "/api/v1/request" }.getHeader("X-API-User"))
        settings.removeProfile("kid-2")
    }

    @Test
    fun seerrErrorTextIsPassedOn() {
        try { seerr.request("movie", 999, emptyList(), null); fail("no error") } catch (e: SeerrException) {
            assertEquals("Quota reached", e.message)
        }
    }

    @Test
    fun cacheExpiresAfterAWeekAndKeepsTheNewest() {
        val dir = File(cacheDir, "c")
        val cache = SeerrCache(dir, maxEntries = 2) // keeps 4 files: 2 people, details and filmography each
        val now = System.currentTimeMillis()
        cache.put("a", "1")
        assertEquals("1", cache.get("a"))
        assertNull(cache.get("a", now = now + 8L * 24 * 3600_000))
        // Older files first, a the oldest; each one's time is set right after it is written.
        File(dir, "a.json").setLastModified(now - 10_000)
        listOf("b", "c", "d", "e").forEachIndexed { i, k ->
            cache.put(k, k)
            File(dir, "$k.json").setLastModified(now - 5_000 + i * 1000L)
        }
        cache.put("f", "f")
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(listOf("c", "d", "e", "f"), listOf("c", "d", "e", "f").filter { cache.get(it) != null })
    }

    @Test
    fun plexCastAndTmdbId() {
        val plexServer = MockWebServer()
        plexServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"MediaContainer":{"Metadata":[{"ratingKey":"7","type":"movie","title":"Testfilm",
                 "Guid":[{"id":"imdb://tt0000001"},{"id":"tmdb://101"}],
                 "Role":[{"id":1,"tag":"Tess Tester","role":"Rick","filter":"actor=1","thumb":"https://example.invalid/t.jpg"},
                         {"id":2,"tag":"Theo Tester","role":"Morty","filter":"actor=2"}],
                 "Director":[{"id":3,"tag":"Dana Director","filter":"director=3"}],
                 "Writer":[{"id":3,"tag":"Dana Director","filter":"writer=3"}]}]}}""")
        }
        plexServer.start()
        settings.plexUrl = plexServer.url("/").toString()
        settings.plexToken = "main"
        val item = PlexClient(OkHttpClient(), settings).metadata("7")
        plexServer.shutdown()
        assertEquals(101, item.tmdbId)
        assertEquals(listOf("Tess Tester", "Theo Tester", "Dana Director"), item.people.map { it.name })
        assertEquals("actor=1", item.people.first().plexFilter)
        assertEquals("Director, Writer", item.people.last().job)
        assertEquals(listOf("Tess Tester", "Dana Director"), stripCast(item.people, actors = 1).map { it.name })
    }

    @Test
    fun stripShowsActorsThenDirectors() {
        val people = (1..15).map { CastMember("Actor $it", "Role $it", null, null) } +
            CastMember("Pat Producer", null, "Producer", null) + CastMember("Dana Director", null, "Director", null)
        val strip = stripCast(people)
        assertEquals(13, strip.size)
        assertEquals("Dana Director", strip.last().name)
    }
}

/** A Seerr that knows a few fictitious titles and people. */
object MockSeerr {
    private const val Users = """{"pageInfo":{"results":3},"results":[
        {"id":1,"displayName":"MEMO Tester","plexUsername":"memotester"},
        {"id":11,"displayName":"Other"},
        {"id":12,"displayName":"Tester Kind"}]}"""

    fun dispatcher(log: MutableList<RecordedRequest>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            synchronized(log) { log += request }
            if (request.getHeader("X-Api-Key") != "test-key") return MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}""")
            val path = request.path.orEmpty().substringBefore('?')
            fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
            return when {
                path == "/api/v1/auth/me" -> json("""{"id":1,"displayName":"MEMO Tester"}""")
                path == "/api/v1/user" -> json(Users)
                path == "/api/v1/search" -> json("""{"page":1,"results":[
                    {"id":101,"mediaType":"movie","title":"Testfilm","releaseDate":"2019-05-01","posterPath":"/p101.jpg","mediaInfo":{"status":5,"ratingKey":"7"}},
                    {"id":300,"mediaType":"tv","name":"Testserie","firstAirDate":"2021-02-01","posterPath":"/p300.jpg"},
                    {"id":102,"mediaType":"movie","title":"Zweiter Testfilm","releaseDate":"2024-01-01","posterPath":"/p102.jpg","mediaInfo":{"status":2}},
                    {"id":501,"mediaType":"person","name":"Tess Tester","profilePath":"/t.jpg","knownFor":[]}]}""")
                path == "/api/v1/person/501" -> json("""{"id":501,"name":"Tess Tester","birthday":"1980-03-04","placeOfBirth":"Teststadt",
                    "knownForDepartment":"Acting","biography":"Tess Tester is a fictitious actor used in Masal's tests. ${"She plays in many test films. ".repeat(12)}",
                    "profilePath":"/t.jpg"}""")
                path == "/api/v1/person/501/combined_credits" -> json("""{"cast":[
                    {"id":101,"mediaType":"movie","title":"Testfilm","releaseDate":"2019-05-01","posterPath":"/p101.jpg","character":"Rick","mediaInfo":{"status":5,"ratingKey":"7"}},
                    {"id":300,"mediaType":"tv","name":"Testserie","firstAirDate":"2021-02-01","posterPath":"/p300.jpg","character":"Ruth","mediaInfo":{"status":4,"ratingKey":"8"}},
                    {"id":102,"mediaType":"movie","title":"Zweiter Testfilm","releaseDate":"2024-01-01","posterPath":"/p102.jpg","character":"","mediaInfo":{"status":2}},
                    {"id":900,"mediaType":"tv","name":"Late Test Show","firstAirDate":"2001-01-01","character":"Herself"}],
                   "crew":[{"id":101,"mediaType":"movie","title":"Testfilm","releaseDate":"2019-05-01","posterPath":"/p101.jpg","job":"Director","mediaInfo":{"status":5,"ratingKey":"7"}}]}""")
                path == "/api/v1/tv/300" -> json("""{"id":300,"name":"Testserie","firstAirDate":"2021-02-01","posterPath":"/p300.jpg","backdropPath":"/b300.jpg",
                    "overview":"A fictitious series.","genres":[{"name":"Drama"}],"episodeRunTime":[45],
                    "seasons":[{"seasonNumber":0,"name":"Specials","episodeCount":2},{"seasonNumber":1,"name":"Season 1","episodeCount":8},
                        {"seasonNumber":2,"name":"Season 2","episodeCount":8},{"seasonNumber":3,"name":"Season 3","episodeCount":10}],
                    "mediaInfo":{"status":4,"ratingKey":"8","seasons":[{"seasonNumber":1,"status":5}],
                        "requests":[{"createdAt":"2026-11-01T10:00:00.000Z","seasons":[{"seasonNumber":2}]}]},
                    "credits":{"cast":[{"id":502,"name":"Theo Tester","character":"Morty","order":1},{"id":501,"name":"Tess Tester","character":"Rick","order":0}],
                        "crew":[{"id":503,"name":"Dana Director","job":"Producer"},{"id":503,"name":"Dana Director","job":"Director"},{"id":504,"name":"Sam Sound","job":"Sound"}]}}""")
                path == "/api/v1/movie/101" -> json("""{"id":101,"title":"Testfilm","releaseDate":"2019-05-01","runtime":125,"posterPath":"/p101.jpg",
                    "overview":"A fictitious film.","genres":[{"name":"Comedy"}],"mediaInfo":{"status":5,"ratingKey":"7"},
                    "credits":{"cast":[{"id":501,"name":"Tess Tester","character":"Rick","order":0}],"crew":[{"id":501,"name":"Tess Tester","job":"Director"}]}}""")
                path == "/api/v1/movie/102" -> json("""{"id":102,"title":"Zweiter Testfilm","releaseDate":"2024-01-01","runtime":95,"posterPath":"/p102.jpg",
                    "overview":"Another fictitious film.","genres":[],"credits":{"cast":[],"crew":[]}}""")
                path == "/api/v1/request" && request.method == "POST" ->
                    if (request.body.clone().readUtf8().contains("\"mediaId\":999")) MockResponse().setResponseCode(403).setBody("""{"message":"Quota reached"}""")
                    else json("""{"id":1}""").setResponseCode(201)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }
}
