package com.alike.masal

import androidx.test.core.app.ApplicationProvider
import com.alike.masal.data.HomeRows
import com.alike.masal.data.Language
import com.alike.masal.data.PlexClient
import com.alike.masal.data.PlexHub
import com.alike.masal.data.PlexItem
import com.alike.masal.data.PlexProfile
import com.alike.masal.data.PlexSection
import com.alike.masal.data.ProfileRejectedException
import com.alike.masal.data.Settings
import com.alike.masal.data.WrongPinException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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

/** Profiles (docs/specs/profiles.md): plex.tv Home members, signing in as one, rows per profile. Fictitious data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfilesTest {
    private val server = MockWebServer()
    private lateinit var settings: Settings
    private lateinit var plex: PlexClient
    private val requests = mutableListOf<RecordedRequest>()

    @Before
    fun init() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        Language.init(context)
        settings = Settings(context)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                val path = request.path.orEmpty()
                val token = request.getHeader("X-Plex-Token")
                return when {
                    path == "/api/v2/home/users" -> json("""{"users":[
                        {"uuid":"admin-1","title":"MEMO Tester","admin":true,"protected":false},
                        {"uuid":"kid-2","title":"Tester Kind","admin":false,"protected":false},
                        {"uuid":"pin-3","title":"Tester PIN","admin":false,"protected":true}]}""")
                    path.startsWith("/api/v2/home/users/pin-3/switch") ->
                        if (path.endsWith("pin=1234")) json("""{"authToken":"user-pin"}""") else MockResponse().setResponseCode(401)
                    path.startsWith("/api/v2/home/users/kid-2/switch") -> json("""{"authToken":"user-kid"}""")
                    path.startsWith("/api/v2/resources") -> json("""[
                        {"name":"Other","provides":"server","accessToken":"wrong-server","connections":[{"address":"10.9.9.9","uri":"http://10.9.9.9:32400"}]},
                        {"name":"Ours","provides":"server","accessToken":"server-${token}","connections":[{"address":"${server.hostName}","uri":"${server.url("/").toString().trimEnd('/')}"}]}]""")
                    path.startsWith("/library/onDeck") -> when (token) {
                        "revoked" -> MockResponse().setResponseCode(401)
                        else -> json("""{"MediaContainer":{"Metadata":[{"ratingKey":"7","type":"movie","title":"Testfilm"}]}}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        settings.plexUrl = server.url("/").toString()
        settings.plexToken = "main"
        plex = PlexClient(OkHttpClient(), settings).also { it.accountBase = server.url("/").toString().trimEnd('/') }
    }

    @After
    fun stop() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun membersWithoutTheMainAccount() {
        val users = plex.homeUsers()
        assertEquals(listOf("Tester Kind", "Tester PIN"), users.map { it.name })
        assertEquals(listOf(false, true), users.map { it.protected })
        assertEquals("main", requests.single().getHeader("X-Plex-Token"))
    }

    @Test
    fun signInGivesTheServerToken() {
        assertEquals("server-user-kid", plex.profileToken("kid-2", null))
        assertEquals("server-user-pin", plex.profileToken("pin-3", "1234"))
        // The switch goes out as the main account, the server lookup as the member.
        val switch = requests.first { it.path!!.contains("/switch") }
        assertEquals("POST", switch.method)
        assertEquals("main", switch.getHeader("X-Plex-Token"))
        assertEquals("user-kid", requests.first { it.path!!.startsWith("/api/v2/resources") }.getHeader("X-Plex-Token"))
    }

    @Test
    fun wrongPinIsRefused() {
        try { plex.profileToken("pin-3", "0000"); fail("wrong PIN accepted") } catch (_: WrongPinException) { }
    }

    @Test
    fun profileRequestsGoOutAsTheMember() {
        val member = PlexClient(OkHttpClient(), settings, PlexProfile("kid-2", "Tester Kind", "server-user-kid"))
        val deck = member.onDeck()
        assertEquals("server-user-kid", requests.last().getHeader("X-Plex-Token"))
        assertEquals("kid-2", deck.single().profile)
        assertNull(plex.onDeck().single().profile)
        assertTrue(member.streamUrl("/library/parts/1/file.mkv").endsWith("X-Plex-Token=server-user-kid"))
    }

    @Test
    fun revokedTokenIsReported() {
        val member = PlexClient(OkHttpClient(), settings, PlexProfile("kid-2", "Tester Kind", "revoked"))
        try { member.onDeck(); fail("revoked token accepted") } catch (_: ProfileRejectedException) { }
    }

    @Test
    fun rowsPerProfile() {
        val p = PlexProfile("kid-2", "Tester Kind", "t")
        val movies = PlexSection("1", "Movies", "movie", null)
        fun item(key: String) = PlexItem(key, "collection", "Saga $key", "", null, null, null, 0, 0, null, null)
        val hubs = listOf(PlexHub("home.movies.recent", "Recently Added Movies", emptyList()),
            PlexHub("custom.collection.1.5.5", "Watchlist", emptyList()))
        val options = HomeRows.profileOptions(p, "Continue Watching", hubs, listOf(movies to item("5"), movies to item("6")))
        assertEquals(listOf("p:kid-2:continue", "p:kid-2:home.movies.recent", "p:kid-2:collection:5", "p:kid-2:collection:6"), options.map { it.id })
        assertEquals(listOf("continue", "home.movies.recent", "collection:5", "collection:6"), options.map { it.rowId })
        assertEquals(listOf("Plex", "Plex", "Movies", "Movies"), options.map { it.group })
        assertTrue(options.all { it.profile == "kid-2" })
        // Only the profile's Continue Watching is on by default.
        assertEquals(listOf(true, false, false, false), options.map { settings.homeRowShown(it) })

        settings.saveProfile(p)
        settings.setHomeRowShown(options[2], true)
        settings.setHomeRowShown(options[0], false)
        assertTrue(settings.homeRowShown(options[2]))
        assertFalse(settings.homeRowShown(options[0]))
        settings.removeProfile(p.id)
        assertTrue(settings.profiles.isEmpty())
        assertEquals(listOf(true, false, false, false), options.map { settings.homeRowShown(it) })
    }
}
