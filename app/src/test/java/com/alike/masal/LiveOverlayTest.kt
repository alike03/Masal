package com.alike.masal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.alike.masal.data.Channel
import com.alike.masal.data.Programme
import com.alike.masal.player.LiveOverlay
import com.alike.masal.player.LiveState
import com.alike.masal.ui.MasalTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The live player's overlay with fictitious channels and guide: info banner (with and without guide) and channel list. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class LiveOverlayTest {
    @get:Rule
    val rule = createComposeRule()

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(800)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/live-shots/$name.png")
    }

    @Test
    fun overlay() {
        val now = System.currentTimeMillis()
        val channels = (1..12).map { n ->
            Channel("test|Testkanal $n", "Testkanal $n HD", null, if (n <= 6) "Nachrichten" else "Sport", "http://example.invalid/$n.ts",
                if (n == 2) null else "test$n")
        }
        val epg = channels.mapNotNull { it.epgId }.associateWith { id ->
            listOf(
                Programme(now - 25 * 60_000, now + 35 * 60_000, "Abendjournal ($id)",
                    "Fiktive Sendung für den Test: Nachrichten des Tages, Wetter und ein Gespräch im Studio. " +
                        "Die Beschreibung ist absichtlich lang, damit man sieht, wie sie nach vier Zeilen endet."),
                Programme(now + 35 * 60_000, now + 95 * 60_000, "Spätfilm", null),
            )
        }
        val state = LiveState().apply {
            groups = channels.groupBy { it.group }.toList()
            listName = "Nachrichten"
            this.channels = channels.filter { it.group == "Nachrichten" }
            index = 0
            this.epg = epg
            infoVisible = true
        }
        rule.setContent {
            MasalTheme {
                // A calm gradient stands in for the video.
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF3A5068), Color(0xFF1B2733), Color(0xFF4A3A2A))))) {
                    LiveOverlay(state) { _, _, _ -> }
                }
            }
        }
        shot("01-info-banner")
        state.index = 1 // a channel without guide
        shot("02-info-banner-no-guide")
        state.index = 0
        state.infoVisible = false
        state.listVisible = true
        shot("03-channel-list")
    }
}
