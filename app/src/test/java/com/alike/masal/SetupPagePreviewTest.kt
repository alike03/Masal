package com.alike.masal

import androidx.test.core.app.ApplicationProvider
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
        val server = SetupServer(settings, key, {}, channelGroups = { groups })
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        try {
            val html = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:$SETUP_PORT/$key").build()).execute()
                .use { it.body!!.string() }
            File("build/setup-preview.html").writeText(html)
            assertTrue("no password on the page", "demo-pass" !in html)
            assertTrue("no full user name on the page", "demo-user" !in html)
            assertTrue("saved category is preselected", Regex("<option value=\"\\[XX] News HD\" selected>").containsMatchIn(html))
        } finally {
            server.stop()
        }
    }
}
