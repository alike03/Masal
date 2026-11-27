package com.alike.masal

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.unit.dp
import com.alike.masal.ui.EdgePadding
import com.alike.masal.ui.noSliverAtStart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Card rows (home.md): the TV's pivot scrolling can leave the last few dp of an earlier card in the
 * row's start padding. Posters as on Home (112 dp, 14 dp apart), scrolled so the card before the
 * first full one ends 3 dp into the row, as on the TV: the slit must not be drawn, the cut card after
 * it must.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class RowSliverTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val card = Color(0xFFE04020)
    private val background = Color.Black

    @Test
    fun noSlitAtTheRowStart() {
        val plain = LazyListState()
        val guarded = LazyListState()
        rule.setContent {
            Column(Modifier.fillMaxSize().background(background)) {
                listOf("plain" to plain, "guarded" to guarded).forEach { (tag, state) ->
                    LazyRow(
                        state = state,
                        modifier = Modifier.height(60.dp).let { if (tag == "guarded") it.noSliverAtStart(state) else it },
                        contentPadding = PaddingValues(horizontal = EdgePadding),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(20) { Box(Modifier.size(112.dp, 60.dp).background(card)) }
                    }
                }
            }
        }
        // mdpi: 1 dp = 1 px. Card 1 starts 9 dp into the padding (at x 17), card 0 ends at x 3.
        rule.runOnIdle { runBlocking { plain.scrollToItem(1, 9); guarded.scrollToItem(1, 9) } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/row-shots/sliver.png")
        val file = java.io.File("build/outputs/roborazzi/build/row-shots/sliver.png").takeIf { it.exists() }
            ?: java.io.File("build/row-shots/sliver.png")
        val px = android.graphics.BitmapFactory.decodeFile(file.path)
        // The plain row is the top 60 px, the guarded one the 60 px below.
        fun at(x: Int, row: Int) = px.getPixel(x, row * 60 + 30)
        // The setup reproduces the slit without the guard...
        assertEquals("slit expected without the guard", card.toArgb(), at(1, 0))
        // ...and the guard removes it, keeping the cut card and the padding gap before it.
        for (x in 0..3) assertEquals("slit drawn at x $x", background.toArgb(), at(x, 1))
        assertEquals("cut card missing", card.toArgb(), at(20, 1))
        assertEquals("next card missing", card.toArgb(), at(150, 1))
    }
}
