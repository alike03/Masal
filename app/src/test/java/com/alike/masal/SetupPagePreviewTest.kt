package com.alike.masal

import androidx.test.core.app.ApplicationProvider
import com.alike.masal.data.HomeRowOption
import com.alike.masal.data.IptvSource
import com.alike.masal.data.Settings
import com.alike.masal.setup.SETUP_PORT
import com.alike.masal.setup.SetupServer
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Serves the QR setup page with fictitious settings and saves it as build/setup-preview.html,
 * to look at the page without a TV. Also checks that saved sources never show their login.
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
        val key = SetupServer.randomKey()
        val groups = listOf("[XX] News HD", "[XX] Sport HD", "[XX] Kids", "[XX] Movies FHD")
        val rows = listOf(
            HomeRowOption("home.movies.recent", "Recently Added Movies", "Plex", true),
            HomeRowOption("collection:11", "Example Saga", "Movies", true),
            HomeRowOption("collection:12", "Example Saga", "Shows", false),
        )
        var reloads = 0
        val server = SetupServer(settings, key, { reloads++ }, channelGroups = { groups }, homeRows = { rows })
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val html = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:$SETUP_PORT/$key").build()).execute()
                .use { it.body!!.string() }
            File("build/setup-preview.html").writeText(html)
            assertTrue("no password on the page", "demo-pass" !in html)
            assertTrue("no full user name on the page", "demo-user" !in html)
            assertTrue("saved category is preselected", Regex("<option value=\"\\[XX] News HD\" selected>").containsMatchIn(html))
            assertTrue("collections grouped by library", "<legend>Movies</legend>" in html && "<legend>Shows</legend>" in html)

            // Save rows: Recently Added off (unchecked), the second Example Saga on, the first stays on.
            val form = okhttp3.FormBody.Builder().add("action", "rows")
                .apply { rows.forEach { add("all", it.id) } }
                .add("row", "collection:11").add("row", "collection:12").build()
            OkHttpClient.Builder().followRedirects(false).build()
                .newCall(Request.Builder().url("http://127.0.0.1:$SETUP_PORT/$key").post(form).build()).execute().close()
            assertTrue("unchecked default row is off", settings.homeRowsOff == setOf("home.movies.recent"))
            assertTrue("checked extra row is on", settings.homeRowsOn == setOf("collection:12"))
            assertTrue("rows shown as chosen", rows.map { settings.homeRowShown(it) } == listOf(false, true, true))
            assertTrue("changing rows does not reload channels and Plex", reloads == 0)
        } finally {
            server.stop()
        }
    }
}
