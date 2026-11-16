package com.alike.masal

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import com.alike.masal.data.PlexItem
import com.alike.masal.data.TvApp
import com.alike.masal.ui.VideoPreviews
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home and Settings with the device in German and in Turkish (fictitious data). Shots: build/locale-shots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class LocaleScreensBase(private val lang: String, private val continueWatching: String, private val settings: String) {
    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun offline() { com.alike.masal.data.Backdrops.base = "http://127.0.0.1:9" } // no photo of the day from the internet
    }

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun press(code: Int, times: Int = 1) = repeat(times) {
        rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        rule.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
    }

    @Test
    fun screens() {
        VideoPreviews.enabled = false; com.alike.masal.ui.ImageFades.enabled = false
        Graph.plex.accountBase = "http://127.0.0.1:9"
        val vm = ViewModelProvider(rule.activity)[MasalViewModel::class.java]
        Thread.sleep(600); rule.waitForIdle()
        fun banner(color: Int) = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }.asImageBitmap()
        vm.apps.value = listOf(TvApp("com.example.player", "Testplayer", banner(0xFF2E6F8E.toInt()), true))
        vm.homeApps.value = listOf("com.example.player")
        val film = PlexItem("101", "movie", "Testfilm Alpha", "2024", null, null, null, 1_200_000, 6_000_000, null,
            "Fiktiver Inhalt für den Test.", year = 2024)
        vm.onDeck.value = listOf(film)
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/locale-shots/$lang-01-home.png")
        assertTrue("Continue Watching in $lang", rule.onAllNodesWithText(continueWatching).fetchSemanticsNodes().isNotEmpty())
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_DOWN, times = 3)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/locale-shots/$lang-02-settings.png")
        assertTrue("Settings in $lang", rule.onAllNodesWithText(settings).fetchSemanticsNodes().isNotEmpty())
    }
}

@Config(sdk = [34], qualifiers = "de-w960dp-h540dp-land-xhdpi")
class GermanScreensTest : LocaleScreensBase("de", "Weiterschauen", "Einstellungen")

@Config(sdk = [34], qualifiers = "tr-w960dp-h540dp-land-xhdpi")
class TurkishScreensTest : LocaleScreensBase("tr", "İzlemeye devam et", "Ayarlar")
