package com.alike.masal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import com.alike.masal.data.TrailerService
import com.alike.masal.data.CastMember
import com.alike.masal.data.PlexItem
import com.alike.masal.data.SeerrTitle
import com.alike.masal.player.PlexActions
import com.alike.masal.player.PlexOverlay
import com.alike.masal.player.PlexUiState
import com.alike.masal.ui.Bg
import com.alike.masal.ui.ImageFades
import com.alike.masal.ui.MasalTheme
import com.alike.masal.ui.PeopleActions
import com.alike.masal.ui.PersonScreen
import com.alike.masal.ui.SeerrTitleScreen
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Person page, Seerr title page and the paused player's cast row against a mock Seerr (people.md, seerr.md). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class PeopleScreensTest {
    @get:Rule
    val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val shots = "build/people-shots"
    private val opened = mutableListOf<String>()
    /** YouTube video ids Masal looked up a stream for; tests never reach YouTube. */
    private val youTube = mutableListOf<String>()
    private val actions = PeopleActions(
        onPerson = { opened += "person:" + it.name },
        onSeerrTitle = { opened += "seerr:" + it.title },
        onPlexTitle = { rk, _ -> opened += "plex:$rk" },
    )

    @Before
    fun init() {
        ImageFades.enabled = false
        server.dispatcher = MockSeerr.dispatcher(requests)
        server.start()
        Graph.settings.seerrUrl = server.url("/").toString()
        Graph.settings.seerrKey = "test-key"
        Graph.seerr.cache.clear()
        Graph.trailers = TrailerService { synchronized(youTube) { youTube += it }; null }
    }

    @After
    fun stop() { server.shutdown(); Graph.settings.clearSeerr(); Graph.seerr.cache.clear() }

    private fun waitFor(text: String) {
        runCatching { rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { rule.onRoot().printToLog("PEOPLE"); println(rule.onRoot().printToString()); throw it }
    }

    /** TV surfaces react to OK, not to touch: press through the click action. */
    private fun press(text: String) {
        // A poster's title sits below its card; the card holds the picture described by the title.
        rule.onAllNodes(hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text) or hasContentDescription(text))), useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = rule.setContent {
        MasalTheme { Box(Modifier.fillMaxSize().background(Bg)) { content() } }
    }

    @Test
    fun personPage() {
        show { PersonScreen(CastMember("Tess Tester", null, null, null, tmdbId = 501), true, null, actions) }
        waitFor("Zweiter Testfilm")
        waitFor("Born")
        rule.onRoot().captureRoboImage("$shots/01-person.png")
        assertEquals(1, rule.onAllNodes(hasText("In Plex"), useUnmergedTree = true).fetchSemanticsNodes().size) // filter tab
        assertEquals(2, rule.onAllNodes(hasText("Available"), useUnmergedTree = true).fetchSemanticsNodes().size) // two badges
        // In Plex: Masal's page; not in Plex: Seerr's page.
        press("Testfilm")
        press("Zweiter Testfilm")
        assertEquals(listOf("plex:7", "seerr:Zweiter Testfilm"), opened)
        rule.mainClock.advanceTimeBy(600) // the page has put the focus on its first card
        rule.waitForIdle()
        // As with the remote: "More" has the focus when OK opens the biography.
        rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("More")), useUnmergedTree = true)[0]
            .performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        press("More")
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$shots/02-biography.png")
        // Back closes the biography (the remote's key through the activity, as on the TV).
        val bio = "is a fictitious actor"
        assertTrue(rule.onAllNodes(hasText(bio, substring = true)).fetchSemanticsNodes().size > 1)
        rule.mainClock.advanceTimeBy(200) // the biography takes the focus, as on the TV
        rule.waitForIdle()
        rule.onAllNodes(isFocused()).assertCountEquals(1)
        rule.onAllNodes(isFocused() and hasAnyDescendant(hasText(bio, substring = true))).assertCountEquals(1)
        rule.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
        rule.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        rule.waitForIdle()
        assertEquals("biography closed", 1, rule.onAllNodes(hasText(bio, substring = true)).fetchSemanticsNodes().size)
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
        rule.onAllNodes(isFocused() and hasAnyDescendant(hasText("More")), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun seriesRequestWithSeasons() {
        show { SeerrTitleScreen("tv", 300, true, null, actions) }
        waitFor("A fictitious series.")
        rule.onRoot().captureRoboImage("$shots/03-seerr-series.png")
        press("Request")
        // First who asks (Seerr's users), then the seasons.
        waitFor("Who is asking?")
        waitFor("Tester Kind")
        rule.onRoot().captureRoboImage("$shots/04a-who.png")
        press("Tester Kind")
        waitFor("Request seasons")
        rule.onRoot().captureRoboImage("$shots/04-seasons.png")
        // Season 1 is in Plex, season 2 requested: only season 3 is left and preselected.
        press("Request 1 season")
        rule.waitUntil(5_000) { synchronized(requests) { requests.any { it.path == "/api/v1/request" } } }
        rule.waitForIdle()
        val request = requests.last { it.path == "/api/v1/request" }
        val body = JSONObject(request.body.readUtf8())
        assertEquals("[3]", body.getJSONArray("seasons").toString())
        assertEquals("as the person picked", "12", request.getHeader("X-API-User"))
        rule.onRoot().captureRoboImage("$shots/05-requested.png")
    }

    @Test
    fun filmRequestAsks() {
        show { SeerrTitleScreen("movie", 102, true, null, actions) }
        waitFor("Another fictitious film.")
        press("Request")
        // Picking who asks sends a film's request at once.
        waitFor("Who is asking?")
        waitFor("Other")
        rule.onRoot().captureRoboImage("$shots/06-film-who.png")
        press("Other")
        rule.waitUntil(5_000) { synchronized(requests) { requests.any { it.path == "/api/v1/request" } } }
        rule.waitForIdle()
        val request = requests.last { it.path == "/api/v1/request" }
        assertEquals(102, JSONObject(request.body.readUtf8()).getInt("mediaId"))
        assertEquals("as the person picked", "11", request.getHeader("X-API-User"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun filmInPlexCanBePlayedFromItsSeerrPage() {
        val played = mutableListOf<String>()
        val withPlay = PeopleActions(actions.onPerson, actions.onSeerrTitle, actions.onPlexTitle, onPlayPlex = { rk, _ -> played += rk },
            onTrailer = { played += "yt:$it" })
        Graph.trailers = TrailerService { synchronized(youTube) { youTube += it }; "https://stream.example/index.m3u8" }
        show { SeerrTitleScreen("movie", 101, false, null, withPlay) }
        rule.waitUntil(5_000) { synchronized(requests) { requests.any { it.path?.startsWith("/api/v1/movie/101") == true } } }
        // No Plex trailer: the YouTube one, its stream found by Masal, in Masal's own player in its
        // own full-screen window (a black box without video in tests); Down hides it and shows the details.
        rule.waitUntil(5_000) { synchronized(youTube) { "trailer01xx" in youTube } }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onAllNodes(isRoot()).assertCountEquals(2)
        rule.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(Key.DirectionDown) }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onAllNodes(isRoot()).assertCountEquals(1)
        rule.onRoot().captureRoboImage("$shots/11-trailer-details.png")
        rule.onNodeWithText("Open").assertExists()
        press("Play")
        assertEquals(listOf("7"), played)
        assertEquals("the YouTube app is only the fallback", listOf("7"), played)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pausedPlayerOffersTheCastBelowTheButtons() {
        val state = PlexUiState().apply {
            item = PlexItem("7", "movie", "Testfilm", "", null, null, null, 0, 0, null, null)
            controls = true; playing = false; buffering = false
            position = 1_800_000; buffered = 2_000_000; duration = 6_000_000
            cast = listOf(CastMember("Tess Tester", "Rick", null, null, 501), CastMember("Theo Tester", "Morty", null, null, 502),
                CastMember("Dana Director", null, "Director", null, 503))
        }
        val people = mutableListOf<String>()
        show { PlexOverlay(state, PlexActions({}, {}, {}, {}, {}, openPerson = { people += it.name })) }
        rule.waitForIdle()
        // Paused: only the chevron, the cast row stays closed.
        rule.onRoot().captureRoboImage("$shots/07-player-cast-hint.png")
        rule.onNodeWithContentDescription("Cast & Crew").assertExists()
        rule.onNodeWithText("Cast & Crew").assertDoesNotExist()
        rule.onNodeWithText("as Rick").assertDoesNotExist()
        // Down from the buttons opens the row on its first person, whatever the button.
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight); pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertTrue(state.castOpen)
        rule.onNodeWithText("as Rick").assertExists()
        assertEquals(0, state.castIndex)
        rule.onNode(isFocused()).assertExists()
        rule.onRoot().captureRoboImage("$shots/08-player-cast-open.png")
        rule.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        assertEquals(1, state.castIndex)
        // Up closes the row again.
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        assertFalse(state.castOpen)
        rule.onNodeWithText("as Rick").assertDoesNotExist()
        // Reopened, it starts at the first person again; OK on the round photo opens the person.
        rule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertEquals(0, state.castIndex)
        rule.onAllNodes(hasClickAction() and hasAnyDescendant(hasText("TT")), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("Tess Tester"), people)
        // Playing: the row stays open; closed, the chevron is offered while playing too.
        state.playing = true
        rule.waitForIdle()
        assertTrue(state.castOpen)
        rule.onNodeWithText("Cast & Crew").assertExists()
        rule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        rule.waitForIdle()
        assertFalse(state.castOpen)
        rule.onNodeWithContentDescription("Cast & Crew").assertExists()
        // Without a cast: nothing.
        state.cast = emptyList()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Cast & Crew").assertDoesNotExist()
    }

    @Suppress("unused")
    private fun title(t: SeerrTitle) = t.title
}
