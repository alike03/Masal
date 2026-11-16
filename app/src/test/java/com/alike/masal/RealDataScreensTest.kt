package com.alike.masal

import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.alike.masal.data.IptvSource
import com.alike.masal.data.TvApp
import com.alike.masal.ui.VideoPreviews
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/**
 * Same app, but fed with the real library from snapshot/snapshot.zip (made on the Shield
 * by the debug SnapshotReceiver). A local server answers like Plex and the IPTV
 * provider. Skipped when no snapshot exists. Screens: app/build/outputs/roborazzi/build/real-shots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class RealDataScreensTest {
    companion object {
        private val zipFile = File("../snapshot/snapshot.zip")

        @JvmStatic
        @BeforeClass
        fun setup() { VideoPreviews.enabled = false; com.alike.masal.ui.ImageFades.enabled = false }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private lateinit var snap: SnapshotServer
    private val shots = "build/real-shots"
    private fun entry(name: String) = snap.entry(name)

    private fun startServer(): Triple<JSONObject, JSONObject, String> {
        snap = SnapshotServer(zipFile).also { it.start() }
        val source = entry("source.json")?.let { JSONObject(String(it)) }
        return Triple(JSONObject(String(entry("settings.json")!!)), source ?: JSONObject(), snap.base)
    }

    @After
    fun stop() { if (::snap.isInitialized) snap.stop() }

    private fun settle(ms: Long = 2_500) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            Thread.sleep(100); rule.mainClock.advanceTimeBy(100); rule.waitForIdle()
        }
    }

    private fun press(code: Int, times: Int = 1, wait: Long = 600) = repeat(times) {
        rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        settle(wait)
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + node.children.flatMap { texts(it) }

    private fun focused() = rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().lastOrNull()
        ?.let { texts(it).joinToString(" ") } ?: "(nothing)"

    /** Moves focus straight to the element showing [text] (a tab or a menu entry). */
    private fun focusText(text: String, wait: Long = 1_000) {
        rule.onAllNodesWithText(text).fetchSemanticsNodes().size.let { println("focusText $text: $it nodes") }
        rule.onAllNodesWithText(text)[0].performSemanticsAction(SemanticsActions.RequestFocus)
        settle(wait)
        println("focus -> ${focused()}")
    }

    private fun shot(name: String) {
        settle(2_000) // artwork loads asynchronously
        println("SHOT $name, focus: ${focused()}")
        rule.onRoot().captureRoboImage("$shots/$name.png")
    }

    @Test
    fun realScreens() {
        assumeTrue("no snapshot/snapshot.zip", zipFile.exists())
        val (saved, source, base) = startServer()
        val vm = ViewModelProvider(rule.activity)[MasalViewModel::class.java]

        // Point the app at the stand-in server; the source id stays the same so favourites and history match.
        val s = Graph.settings
        s.plexUrl = base
        s.plexToken = "test-token"
        if (source.has("id")) s.sources = listOf(IptvSource(source.getString("id"), source.optString("name"), "xtream",
            epgUrl = "$base/xmltv.php", server = base, user = "test", pass = "test"))
        val fav = saved.getJSONArray("favorites").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val recent = saved.getJSONArray("recent").let { a -> (0 until a.length()).map { a.getString(it) } }
        s.favorites = fav; s.recentChannels = recent
        // Home live TV row: from the snapshot, else from MASAL_TEST_HOME_GROUP / _TITLE in .env.
        s.homeLiveGroup = saved.optString("homeLiveGroup").ifBlank { System.getenv("MASAL_TEST_HOME_GROUP").orEmpty() }
        s.homeLiveTitle = saved.optString("homeLiveTitle").ifBlank { System.getenv("MASAL_TEST_HOME_TITLE").orEmpty() }
        vm.favorites.value = fav; vm.recentChannels.value = recent
        s.changed()
        com.alike.masal.data.Backdrops.base = base
        vm.loadBackdrops()

        // Apps from the Shield (banners saved in the snapshot).
        entry("apps.json")?.let { raw ->
            val arr = JSONArray(String(raw))
            vm.apps.value = (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val png = entry("apps/" + o.getString("file")) ?: return@mapNotNull null
                TvApp(o.getString("pkg"), o.getString("label"), BitmapFactory.decodeByteArray(png, 0, png.size).asImageBitmap(),
                    o.getBoolean("isBanner"))
            }
            // Pin a few apps to Home, as the user would on the Apps page.
            val pins = vm.apps.value.map { it.pkg }.filter { p -> listOf("netflix", "youtube", "disney", "plex", "spotify").any { p.contains(it) } }
                .ifEmpty { vm.apps.value.take(4).map { it.pkg } }
            s.homeApps = pins; vm.homeApps.value = pins
        }

        // Wait for channels, guide and Plex to load from the stand-in server.
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline && (vm.channels.value.isEmpty() || vm.onDeck.value.isEmpty() ||
                vm.loadingChannels.value)) settle(500)
        settle(3_000)
        println("LOADED channels=${vm.channels.value.size} epg=${vm.epg.value.size} onDeck=${vm.onDeck.value.size} " +
            "sections=${vm.sections.value.size} errors=${vm.iptvErrors.value} plex=${vm.plexError.value}")

        shot("01-home-apps")
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 3_000)
        shot("02-home-continue-watching")
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_500)
        shot("03-home-live-row")
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_500)
        shot("04-home-recently-added")
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_000)
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_500)
        shot("05-home-collection-row")

        press(KeyEvent.KEYCODE_BACK)
        shot("06-menu-open")
        // Movies: TV is the second entry, the first library the third.
        press(KeyEvent.KEYCODE_DPAD_DOWN); press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_500)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, wait = 3_000)
        shot("07-movies-recommended")
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_500)
        shot("08-movies-recommended-row2")
        press(KeyEvent.KEYCODE_DPAD_UP); press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, wait = 2_500)
        press(KeyEvent.KEYCODE_DPAD_DOWN); press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_500)
        shot("09-movies-browse")
        repeat(7) { press(KeyEvent.KEYCODE_DPAD_RIGHT); println("right -> ${focused()}") }
        repeat(4) { press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 400); println("down -> ${focused()}") }
        shot("10-movies-browse-letters")
        // The remaining pages are reached by focusing their tab or menu entry directly.
        focusText("Collections", wait = 2_500)
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 800)
        shot("11-movies-collections")
        press(KeyEvent.KEYCODE_DPAD_RIGHT, times = 2, wait = 300)
        press(KeyEvent.KEYCODE_DPAD_CENTER, wait = 3_000)
        shot("12-collection-page")
        press(KeyEvent.KEYCODE_BACK, wait = 1_500)
        focusText("Categories", wait = 2_500)
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 800)
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 800)
        shot("13-movies-categories")
        press(KeyEvent.KEYCODE_DPAD_CENTER, wait = 3_000)
        shot("14-category-page")
        press(KeyEvent.KEYCODE_DPAD_CENTER, wait = 3_000)
        shot("15-movie-detail")
        press(KeyEvent.KEYCODE_BACK, wait = 1_000); press(KeyEvent.KEYCODE_BACK, wait = 1_000)

        val shows = vm.sections.value.firstOrNull { it.type == "show" }?.title
        if (shows != null) {
            press(KeyEvent.KEYCODE_BACK, wait = 800) // menu, on Movies
            press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_500) // TV Shows
            press(KeyEvent.KEYCODE_DPAD_RIGHT, wait = 3_000)
            shot("16-shows-recommended")
            press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_500)
            shot("17-shows-recommended-row2")
        }
        press(KeyEvent.KEYCODE_BACK, wait = 800) // menu, on TV Shows
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_500) // Apps
        press(KeyEvent.KEYCODE_DPAD_RIGHT, wait = 2_500)
        shot("18-apps")
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_000)
        shot("19-apps-on-home")

        // Search: the channel list is real; Plex search is not in the snapshot (shows channels only).
        press(KeyEvent.KEYCODE_BACK, wait = 800) // menu, on Apps
        press(KeyEvent.KEYCODE_DPAD_UP, times = 6, wait = 300) // Search is the top entry
        settle(1_000)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, wait = 1_000)
        println("search focus -> ${focused()}")
        rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0]
            .performSemanticsAction(SemanticsActions.SetText) {
                it(androidx.compose.ui.text.AnnotatedString(System.getenv("MASAL_TEST_SEARCH")?.ifBlank { null } ?: "tv"))
            }
        settle(1_500)
        shot("20-search-typed")
        press(KeyEvent.KEYCODE_DPAD_DOWN, wait = 1_000)
        shot("21-search-result-focused")
    }
}
