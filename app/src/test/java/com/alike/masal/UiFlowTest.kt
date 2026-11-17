package com.alike.masal

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.alike.masal.data.Channel
import com.alike.masal.data.PlexHub
import androidx.compose.ui.test.onAllNodesWithText
import com.alike.masal.data.PlexItem
import com.alike.masal.data.PlexSection
import com.alike.masal.data.TvApp
import com.alike.masal.ui.VideoPreviews
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the real MainActivity with remote key presses on the JVM (Robolectric),
 * using fictitious data only. Screenshots go to app/build/masal-shots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class UiFlowTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun noVideo() {
            VideoPreviews.enabled = false; com.alike.masal.ui.ImageFades.enabled = false
            // The app loads the photo of the day and asks plex.tv for codes: never leave the machine.
            com.alike.masal.data.Backdrops.base = "http://127.0.0.1:9"
        }
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val shots = "build/masal-shots"

    private fun seed(): MasalViewModel {
        Graph.plex.accountBase = "http://127.0.0.1:9" // Settings asks plex.tv for a link code: never leave the machine
        val vm = ViewModelProvider(rule.activity)[MasalViewModel::class.java]
        Thread.sleep(600) // let the (empty) initial loads finish so they do not overwrite the test data
        rule.waitForIdle()
        val groups = listOf("Nachrichten" to 12, "Sport" to 10, "Filme" to 8, "[XX] Testgruppe HD" to 6)
        var n = 0
        val channels = groups.flatMap { (g, count) ->
            (1..count).map { n++; Channel("test|Testkanal $n", "Testkanal $n", null, g, "http://example.invalid/$n.ts", null) }
        }
        vm.channels.value = channels + channels.first() // providers list some channels twice
        Graph.settings.homeLiveGroup = "Testgruppe HD"
        Graph.settings.homeLiveTitle = "Live TV"
        fun banner(color: Int) = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()
        vm.apps.value = listOf(
            TvApp("com.example.player", "Testplayer", banner(0xFF2E6F8E.toInt()), true),
            TvApp("com.example.files", "Testdateien", banner(0xFF8E5A2E.toInt()), true),
            TvApp("com.example.music", "Testmusik", banner(0xFF4E7A3A.toInt()), true),
        )
        vm.homeApps.value = listOf("com.example.player", "com.example.music")
        val film = PlexItem(
            "101", "movie", "Testfilm Alpha", "2024", null, null, null, 1_200_000, 6_000_000, null,
            "Fiktiver Inhalt für den Test. Ein langer Beschreibungstext, der zeigen soll, dass der Text " +
                "links bleibt und nicht über den ganzen Bildschirm läuft, auch wenn er mehrere Zeilen braucht.",
            year = 2024, contentRating = "12", rating = 7.5, genres = listOf("Drama", "Test"),
        )
        vm.onDeck.value = listOf(film, film.copy(ratingKey = "102", title = "Testfilm Beta"))
        vm.recentChannels.value = listOf("test|Testkanal 3", "test|Testkanal 14")
        val films = (1..14).map { film.copy(ratingKey = "f$it", title = "Testfilm $it", titleSort = "Testfilm $it", viewOffset = 0) }
        vm.sectionCache["1"] = films
        vm.hubCache["1"] = listOf(PlexHub("movie.recentlyadded.1", "Recently Added in Filme", films))
        vm.sections.value = listOf(PlexSection("1", "Filme", "movie", null), PlexSection("2", "Serien", "show", null))
        rule.waitForIdle()
        return vm
    }

    private fun press(code: Int, times: Int = 1) = repeat(times) {
        rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    private fun texts(node: SemanticsNode): List<String> =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            node.children.flatMap { texts(it) }

    /** Text inside the element that currently has focus. */
    private fun focused(): String =
        rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().lastOrNull()
            ?.let { texts(it).joinToString(" ") } ?: "(nothing)"

    private fun expectFocus(step: String, contains: String) {
        val f = focused()
        println("STEP $step -> focus: $f")
        assertTrue("$step: expected focus on '$contains' but was '$f'", f.contains(contains))
    }

    private fun shot(name: String) = rule.onRoot().captureRoboImage("$shots/$name.png")

    /** Masal as the device's home screen: the Home key returns to Home, Back on Home stays. */
    @Test
    fun homeScreen() {
        val vm = seed()
        vm.homeHubs.value = listOf(PlexHub("collection:5", "Testsammlung", vm.sectionCache.getValue("1")))
        rule.mainClock.advanceTimeBy(1_000)
        expectFocus("start", "Testplayer")
        assertTrue("a collection row says it holds movies",
            rule.onAllNodesWithText("Movies").fetchSemanticsNodes().isNotEmpty())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        expectFocus("in the TV tab", "Last watched")

        rule.runOnUiThread {
            rule.activity.onNewIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME))
        }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        expectFocus("Home key returns to Home", "Testplayer")

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val lower = focused()
        assertTrue("down leaves the apps row", !lower.contains("Testplayer"))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        expectFocus("menu on Home", "Home")
        rule.runOnUiThread { rule.activity.isHome = true }
        rule.waitForIdle()
        press(KeyEvent.KEYCODE_BACK)
        assertTrue("as home screen, Back on Home does not leave", !rule.activity.isFinishing)
        expectFocus("Back in the menu goes to Home's first card", "Testplayer")
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        expectFocus("menu on Home again", "Home")
        rule.runOnUiThread { rule.activity.isHome = false }
        rule.waitForIdle()
        // Not pressed, that would close the test's activity: no enabled Back callback means the
        // system handles Back, which leaves the app.
        assertTrue("as a normal app, Back on Home leaves", !rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    /** A profile's rows (profiles.md): its Continue Watching right after the main one, named; its collection after the main rows. */
    @Test
    fun profileRows() {
        val vm = seed()
        val films = vm.sectionCache.getValue("1")
        val own = films.take(3).map { it.copy(title = "Profilfilm " + it.title.substringAfterLast(' '), profile = "kid-2") }
        vm.profileDecks.value = listOf(PlexHub("p:kid-2:continue", "Continue Watching", own, owner = "Tester Kind"))
        vm.homeHubs.value = listOf(
            PlexHub("collection:5", "Testsammlung", films),
            PlexHub("p:kid-2:collection:5", "Testsammlung", own, owner = "Tester Kind"),
        )
        rule.mainClock.advanceTimeBy(1_000)
        expectFocus("start", "Testplayer")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.mainClock.advanceTimeBy(600)
        assertTrue("main Continue Watching first", rule.onAllNodesWithText("Testfilm Alpha").fetchSemanticsNodes().isNotEmpty())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.mainClock.advanceTimeBy(600)
        assertTrue("the profile's Continue Watching is next",
            rule.onAllNodesWithText("Profilfilm 1").fetchSemanticsNodes().isNotEmpty())
        assertTrue("its row is named after the profile",
            rule.onAllNodesWithText("Tester Kind").fetchSemanticsNodes().isNotEmpty())
        shot("13-profile-row")
    }

    /** Settings with the remote: live TV row category (Back cancels, OK picks) and Home rows. */
    @Test
    fun settingsScreen() {
        val vm = seed()
        val recent = com.alike.masal.data.HomeRowOption("home.movies.recent", "Recently Added Movies", "Plex", true)
        val saga = com.alike.masal.data.HomeRowOption("collection:5", "Testsammlung", "Filme", false)
        vm.homeRowOptions.value = listOf(recent, saga)
        Graph.settings.homeRowsOn = emptySet(); Graph.settings.homeRowsOff = emptySet()
        rule.mainClock.advanceTimeBy(1_000)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 5)
        expectFocus("menu on Settings", "Settings")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        expectFocus("first button", "Plex")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("language row", "Language")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("category row", "Channel category")
        shot("09-settings")

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        expectFocus("picker starts on the saved category", "Testgruppe HD")
        shot("10-settings-category")
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_BACK)
        expectFocus("Back returns to the row", "Channel category")
        assertTrue("Back leaves the category unchanged", Graph.settings.homeLiveGroup == "Testgruppe HD")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_UP)
        expectFocus("previous category", "Filme")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        expectFocus("OK returns to the row with the new value", "Filme")
        assertTrue("OK picks the category", Graph.settings.homeLiveGroup == "Filme")

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("title row", "Row title")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("category order row", "Category order")
        val before = vm.categoryNames()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        expectFocus("order page starts on the first category", before.first())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue("OK and Down move the first category one down", vm.categoryNames().take(2) == listOf(before[1], before[0]))
        expectFocus("focus stays on the moved category", before.first())
        shot("11-settings-category-order")
        press(KeyEvent.KEYCODE_BACK)
        expectFocus("Back puts it down and stays on the page", before.first())
        press(KeyEvent.KEYCODE_BACK)
        expectFocus("Back returns to the row", "Own order")
        vm.setCategoryOrder(emptyList())

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("first Home row", "Recently Added Movies")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("OK switches a default row off", !Graph.settings.homeRowShown(recent))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("collection", "Testsammlung")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("OK switches a collection on", Graph.settings.homeRowShown(saga))
        shot("12-settings-rows")
    }

    @Test
    fun remoteFlow() {
        val vm = seed()
        rule.mainClock.advanceTimeBy(1_000)
        expectFocus("start", "Testplayer")
        shot("01-home")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        expectFocus("left opens menu", "Home")
        shot("02-menu-open")

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("down selects TV", "TV")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        expectFocus("right enters TV tab", "Last watched")
        shot("03-tv-categories")

        press(KeyEvent.KEYCODE_DPAD_DOWN) // Favourites is empty, so the next category is a provider group
        val category = focused()
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        expectFocus("OK on category ($category) enters channels", "Testkanal")
        shot("04-tv-channels")

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        expectFocus("right on channel reaches star", "favourite")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("star adds a favourite", vm.favorites.value.isNotEmpty())
        shot("05-tv-star")

        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_BACK)
        expectFocus("back returns to category", category.substringBefore(" ").ifBlank { "Nachrichten" })
        press(KeyEvent.KEYCODE_BACK)
        expectFocus("back goes to menu", "TV")

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("down selects Filme library", "Filme")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        expectFocus("right enters library (Recommended)", "Testfilm")
        shot("06-library-recommended")

        press(KeyEvent.KEYCODE_DPAD_UP)
        expectFocus("up reaches the library tabs", "Recommended")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        expectFocus("right selects Browse", "Browse")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("down reaches the sort options", if (focused().contains("Year")) "Year" else "Title")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("down reaches the grid", "Testfilm")
        shot("07-library-browse")
        press(KeyEvent.KEYCODE_DPAD_UP)
        press(KeyEvent.KEYCODE_DPAD_UP)
        if (!focused().contains("Recommended")) press(KeyEvent.KEYCODE_DPAD_LEFT)
        expectFocus("back to Recommended", "Recommended")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("down from tabs returns to the row", "Testfilm")

        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_BACK)
        expectFocus("back in menu goes Home", "Home")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        expectFocus("right enters Home on the first app", "Testplayer")
        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("play/pause + right moves the app", vm.homeApps.value.first() == "com.example.music")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("next row is continue watching", "Testfilm")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        expectFocus("then the live TV row", "Testkanal")
        shot("08-home-tv-row")
    }
}
