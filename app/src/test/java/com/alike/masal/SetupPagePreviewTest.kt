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
 * to look at the page without a TV. Also checks the PIN sign-in, that saved sources never
 * show their login, that stored secrets stay with their address and that large bodies are refused.
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
        val moves = mutableListOf<Pair<String?, Int>>()
        val server = SetupServer(ApplicationProvider.getApplicationContext(), settings, SetupAuth(settings) { now }, { reloads++ },
            channelGroups = { groups }, homeRows = { rows },
            homeOrder = { listOf("deck" to "Continue Watching", "hub:recent" to "Recently Added") },
            moveCategory = { name, delta -> moves += name to delta })
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
            // The TV's six sections, in its order, all folded until one is opened.
            val ids = Regex("id=\"s-([a-z]+)\"").findAll(html).map { it.groupValues[1] }.toList()
            assertEquals(listOf("home", "live", "player", "accounts", "phone", "general"), ids)
            assertTrue("folded", "class=\"section card\" id=\"s-home\" open" !in html)
            fun post(vararg fields: Pair<String, String>) = http.newCall(Request.Builder().url(base).header("Cookie", cookie)
                .post(okhttp3.FormBody.Builder().apply { fields.forEach { add(it.first, it.second) } }.build()).build()).execute()
            // Player settings: stored, and the section stays open after saving.
            val saved = post("action" to "player", "size" to "125", "colour" to "yellow", "back" to "15", "forward" to "999")
            assertTrue("back in the player section", saved.header("Location")!!.endsWith("&open=player"))
            saved.close()
            assertEquals(125, settings.subtitleSize)
            assertEquals(15, settings.skipBackSeconds)
            assertEquals("a value not offered is ignored", 30, settings.skipForwardSeconds)
            assertTrue("opened section", "id=\"s-player\" open" in get("$base?open=player", cookie).use { it.body!!.string() })
            // Orders: a category moves through the view model, a Home row into the stored order.
            post("action" to "cat_move", "move" to "down:[XX] Kids").close()
            assertEquals(listOf<Pair<String?, Int>>("[XX] Kids" to 1), moves)
            post("action" to "home_move", "move" to "up:hub:recent").close()
            assertEquals(listOf("hub:recent", "deck"), settings.homeOrder.value)
            post("action" to "home_order_reset").close()
            assertTrue(settings.homeOrder.value.isEmpty())

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
            // Stored secrets go only to the stored address: a new Plex or Seerr address needs them again.
            settings.plexToken = "stored-token"
            post("action" to "plex", "url" to "http://elsewhere.example:32400").close()
            assertEquals("Plex token kept for its own server", "http://plex-server:32400", settings.plexUrl)
            post("action" to "plex", "url" to "http://PLEX-server:32400/").close()
            assertEquals("same server, other spelling", "http://PLEX-server:32400", settings.plexUrl)
            post("action" to "plex", "url" to "http://elsewhere.example:32400", "token" to "new-token").close()
            assertEquals("http://elsewhere.example:32400", settings.plexUrl)
            assertEquals("new-token", settings.plexToken)
            settings.seerrUrl = "http://seerr.example:5055"
            settings.seerrKey = "stored-key"
            post("action" to "seerr", "url" to "http://elsewhere.example:5055").close()
            assertEquals("Seerr key not sent to a new address", "http://seerr.example:5055", settings.seerrUrl)
            // Large bodies are refused before they are read; before signing in only a PIN fits.
            // (Only the headers are sent: the server answers without waiting for the body.)
            fun announce(length: Int, cookieHeader: String = "") = java.net.Socket("127.0.0.1", SETUP_PORT).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(("POST / HTTP/1.1\r\nHost: 127.0.0.1\r\n$cookieHeader" +
                    "Content-Type: application/x-www-form-urlencoded\r\nContent-Length: $length\r\n\r\n").toByteArray())
                socket.getInputStream().bufferedReader().readLine()
            }
            assertTrue("large sign-in refused", announce(5_000).contains(" 413 "))
            assertTrue("large form refused", announce(300_000, "Cookie: $cookie\r\n").contains(" 413 "))
            // A new PIN signs every device out.
            settings.renewSetupPin()
            assertTrue("signed out after a new PIN", "name=\"pin\"" in get(base, cookie).use { it.body!!.string() })
        } finally {
            server.stop()
        }
    }
}
