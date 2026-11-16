package com.alike.masal

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.alike.masal.ui.Ambient
import com.alike.masal.ui.AmbientBackground
import com.alike.masal.ui.ImageFades
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * The colour fill around the picture must not leave a line where it meets the picture's frame.
 * Without a loaded picture the dark background shows in the hole, so any column where the fill
 * lets it through stands out as darker than the column to its right.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class AmbientSeamTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun noLineAtTheFrameEdge() {
        ImageFades.enabled = false
        rule.mainClock.autoAdvance = false // the picture's slow drift never ends, so the UI is never idle
        val tint = 0xFF5A4636.toInt()
        rule.setContent { AmbientBackground(Ambient("seam", "http://example.invalid/none.jpg", colors = List(4) { tint })) }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/ambient-shots/seam.png")
        val file = java.io.File("build/outputs/roborazzi/build/ambient-shots/seam.png").takeIf { it.exists() }
            ?: java.io.File("build/ambient-shots/seam.png")
        val px = android.graphics.BitmapFactory.decodeFile(file.path)
        val left = px.width - (px.width * 0.62f).roundToInt() // the frame's left edge, as laid out
        for (y in listOf(40, px.height / 4, px.height / 2)) {
            fun lum(x: Int) = px.getPixel(x, y).let { android.graphics.Color.red(it) + android.graphics.Color.green(it) + android.graphics.Color.blue(it) }
            for (x in left - 4..left + 4) {
                assertTrue("column $x at y $y is darker than column ${x + 1}: line at the frame edge (frame starts at $left)",
                    lum(x) >= lum(x + 1) - 6)
            }
        }
    }
}
