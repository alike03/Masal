package com.alike.masal

import androidx.test.core.app.ApplicationProvider
import com.alike.masal.data.HomeRowOption
import com.alike.masal.data.IptvSource
import com.alike.masal.data.Settings
import com.alike.masal.setup.SETUP_PORT
import com.alike.masal.setup.SetupAuth
import com.alike.masal.setup.SetupServer
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Serves the setup page with fictitious settings and saves it as build/setup-preview.html,
 * to look at the page without a TV. Also checks the PIN sign-in and that saved sources never
 * show their login.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupPagePreviewTest {
    @Test
    fun preview() {
        val settings = Settings(ApplicationProvider.getApplicationContext())
        settings.plexUrl = "http://plex-server:32400"
        settings.sources = listOf(
            IptvSource("a1", "Example provider", "xtream", server = "http://provider.example:8080", user = "demo-user", pass = "demo-pass"),
            IptvSource("b2", "Example playlist", "m3u", url = "http://lists.example/get.php?username=demo-user&password=demo-pass&type=m3u"),
        )
        settings.homeLiveGroup = "News HD"
        settings.homeLiveTitle = "Live TV"
        val groups = listOf("[XX] News HD", "[XX] Sport HD", "[XX] Kids", "[XX] Movies FHD")
        val rows = listOf(
            HomeRowOption("home.movies.recent", "Recently Added Movies", "Plex", true),
            HomeRowOption("collection:11", "Example Saga", "Movies", true),
            HomeRowOption("collection:12", "Example Saga", "Shows", false),
        )
        var reloads = 0
        var now = 1_000_000L
        val server = SetupServer(ApplicationProvider.getApplicationContext(), settings, SetupAuth(settings) { now }, { reloads++ },
            channelGroups = { groups }, homeRows = { rows })
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        val base = "http://127.0.0.1:$SETUP_PORT/"
        val http = OkHttpClient.Builder().followRedirects(false).build()
        fun get(url: String, cookie: String? = null) = http.newCall(Request.Builder().url(url).apply { cookie?.let { header("Cookie", it) } }.build()).execute()
        fun pin(p: String) = http.newCall(Request.Builder().url(base).post(okhttp3.FormBody.Builder().add("pin", p).build()).build()).execute()
        try {
            // Not signed in: the PIN page, never the settings.
            val locked = get(base).use { it.body!!.string() }
            assertTrue("PIN page first", "name=\"pin\"" in locked && "News HD" !in locked)
            val wrongPin = if (settings.setupPin == "000000") "111111" else "000000"
            assertTrue("wrong PIN refused", "Wrong PIN" in pin(wrongPin).use { it.body!!.string() })
            repeat(4) { pin(wrongPin).close() }
            // Five wrong PINs: even the right one is refused for a minute.
            assertTrue("locked after five tries", "Too many attempts" in pin(settings.setupPin).use { it.body!!.string() })
            now += 61_000
            // The QR code's address opens the PIN page with the PIN filled in, without signing in.
            val qr = get(base + "?pin=" + settings.setupPin)
            assertEquals(200, qr.code)
            assertNull("not signed in by the address", qr.header("Set-Cookie"))
            assertTrue("PIN filled in", "value=\"${settings.setupPin}\"" in qr.use { it.body!!.string() })
            // Signing in from the page sets the cookie; the PIN does not stay in the address.
            val signedIn = pin(settings.setupPin)
            assertEquals(303, signedIn.code)
            assertEquals("/", signedIn.header("Location"))
            val setCookie = signedIn.header("Set-Cookie")!!
            signedIn.close()
            assertTrue("cookie is HttpOnly and SameSite", "HttpOnly" in setCookie && "SameSite=Strict" in setCookie)
            val cookie = setCookie.substringBefore(';')
            val html = get(base, cookie).use { it.body!!.string() }
            File("build/setup-preview.html").writeText(html)
            assertTrue("no password on the page", "demo-pass" !in html)
            assertTrue("no full user name on the page", "demo-user" !in html)
            assertTrue("saved category is preselected", Regex("<option value=\"\\[XX] News HD\" selected>").containsMatchIn(html))
            assertTrue("collections grouped by library", "<legend>Movies</legend>" in html && "<legend>Shows</legend>" in html)

            // Save rows: Recently Added off (unchecked), the second Example Saga on, the first stays on.
            val form = okhttp3.FormBody.Builder().add("action", "rows")
                .apply { rows.forEach { add("all", it.id) } }
                .add("row", "collection:11").add("row", "collection:12").build()
            http.newCall(Request.Builder().url(base).header("Cookie", cookie).post(form).build()).execute().close()
            assertTrue("unchecked default row is off", settings.homeRowsOff == setOf("home.movies.recent"))
            assertTrue("checked extra row is on", settings.homeRowsOn == setOf("collection:12"))
            assertTrue("rows shown as chosen", rows.map { settings.homeRowShown(it) } == listOf(false, true, true))
            assertTrue("changing rows does not reload channels and Plex", reloads == 0)
            // Without the cookie a form is not applied.
            http.newCall(Request.Builder().url(base).post(okhttp3.FormBody.Builder().add("action", "delete").add("id", "a1").build()).build()).execute().close()
            assertEquals(2, settings.sources.size)
            // A new PIN signs every device out.
            settings.renewSetupPin()
            assertTrue("signed out after a new PIN", "name=\"pin\"" in get(base, cookie).use { it.body!!.string() })
        } finally {
            server.stop()
        }
    }
}
