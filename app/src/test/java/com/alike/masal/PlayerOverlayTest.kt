package com.alike.masal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.alike.masal.player.Panel
import com.alike.masal.player.PanelOption
import com.alike.masal.player.PlexActions
import com.alike.masal.player.PlexOverlay
import com.alike.masal.player.PlexUiState
import com.alike.masal.ui.MasalTheme
import com.alike.masal.ui.NetImage
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The Plex player's controls and subtitle panels with a real title (a still image stands in for the video). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class PlayerOverlayTest {
    @get:Rule
    val rule = createComposeRule()
    private val zipFile = File("../snapshot/snapshot.zip")
    private lateinit var snap: SnapshotServer

    @After
    fun stop() { if (::snap.isInitialized) snap.stop() }

    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { Thread.sleep(100); rule.mainClock.advanceTimeBy(100); rule.waitForIdle() }
    }

    @Test
    fun playerScreens() {
        assumeTrue(zipFile.exists())
        snap = SnapshotServer(zipFile).also { it.start() }
        Graph.settings.plexUrl = snap.base
        Graph.settings.plexToken = "test-token"
        val deck = Graph.plex.onDeck()
        val movie = Graph.plex.metadata(deck.first { it.type == "movie" }.ratingKey)
        val state = PlexUiState().apply {
            item = movie; controls = true; playing = true; buffering = false
            duration = movie.duration; position = movie.viewOffset.takeIf { it > 0 } ?: (movie.duration / 3); buffered = position + 90_000
            subtitleLabel = "English"; audioLabel = "English (TRUEHD 7.1)"
        }
        rule.setContent {
            MasalTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NetImage(Graph.plex.image(movie.art, 1920, 1080), null, Modifier.fillMaxSize())
                    PlexOverlay(state, PlexActions({}, {}, {}, {}, {}))
                }
            }
        }
        settle(2_500)
        rule.onRoot().captureRoboImage("build/player-shots/01-controls.png")

        val subs = movie.streams.filter { it.type == 3 }
        state.panel = Panel("Subtitles", listOf(PanelOption("Off") {}) + subs.take(9).map {
            PanelOption(it.title, detail = listOfNotNull(it.codec.uppercase(), if (it.key != null) "External" else null).joinToString("  "),
                selected = it.selected) {}
        } + PanelOption("Search for subtitles…", detail = "OpenSubtitles through your Plex server") {})
        settle(1_200)
        rule.onRoot().captureRoboImage("build/player-shots/02-subtitles.png")

        val found = Graph.plex.searchSubtitles(movie.ratingKey, "en")
        println("SUBTITLE SEARCH en: ${found.size} results, first: ${found.firstOrNull()?.title}")
        state.panel = Panel("Search subtitles", found.map {
            PanelOption(it.title, detail = listOfNotNull(it.language, it.provider, if (it.forced) "Forced" else null).joinToString("  ")) {}
        }, note = "${found.size} found, best matches first")
        settle(1_200)
        rule.onRoot().captureRoboImage("build/player-shots/03-subtitle-search.png")
    }
}
