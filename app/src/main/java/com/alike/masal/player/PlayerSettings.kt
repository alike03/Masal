package com.alike.masal.player

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.alike.masal.R
import com.alike.masal.data.Settings

/** The Plex player's settings panel: choices per setting (spec: docs/specs/player.md). */
object PlayerSettings {
    /** Subtitle size at 100 %. */
    const val SubtitleDp = 21f

    val sizes = listOf(50, 75, 100, 125, 150)
    /** Stored value to label (string resource) and colour. */
    val colors = listOf(
        Choice("white", R.string.player_colour_white, Color.WHITE),
        Choice("yellow", R.string.player_colour_yellow, 0xFFFFE14D.toInt()),
        Choice("grey", R.string.player_colour_grey, 0xFFD2D2D2.toInt()),
        Choice("cyan", R.string.player_colour_cyan, 0xFF7FE6F0.toInt()),
    )
    val backgrounds = listOf(
        Choice("none", R.string.player_background_none, Color.TRANSPARENT),
        Choice("half", R.string.player_background_half, 0x99000000.toInt()),
        Choice("solid", R.string.player_background_solid, Color.BLACK),
    )
    /** Distance of the subtitles above the bottom, in percent of the picture height. */
    val positions = listOf(
        Choice("low", R.string.player_position_low, 3),
        Choice("normal", R.string.player_position_normal, 7),
        Choice("high", R.string.player_position_high, 15),
        Choice("higher", R.string.player_position_higher, 25),
    )
    val skipBack = listOf(5, 10, 15, 30)
    val skipForward = listOf(10, 15, 30, 60)

    class Choice(val key: String, @StringRes val label: Int, val value: Int)

    fun List<Choice>.of(key: String) = firstOrNull { it.key == key } ?: first()

    /** Applies the stored subtitle look to [view]: colour, box or outline, size, position. */
    @OptIn(UnstableApi::class)
    fun applySubtitles(view: SubtitleView, s: Settings, font: Typeface?) {
        val fg = colors.of(s.subtitleColor).value
        val bg = backgrounds.of(s.subtitleBackground)
        val outline = bg.key == "none"
        view.setStyle(CaptionStyleCompat(
            fg, bg.value, Color.TRANSPARENT,
            if (outline) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK,
            Typeface.create(font ?: Typeface.SANS_SERIF, Typeface.BOLD),
        ))
        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, SubtitleDp * s.subtitleSize.coerceIn(25, 300) / 100f)
        view.setApplyEmbeddedFontSizes(false)
        view.setApplyEmbeddedStyles(false) // the chosen colour and box win over styles in the file
        view.setBottomPaddingFraction(positions.of(s.subtitlePosition).value / 100f)
    }
}
