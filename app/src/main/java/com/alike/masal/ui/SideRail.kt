package com.alike.masal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Width of the collapsed menu; screens are laid out to the right of it. */
val RailWidth = 64.dp
/** Font size of the labels; [ItemChrome] is everything around a label in an open entry. */
private val LabelSize = 16.sp
/** Bar 3 + gap 8 + padding 12 + icon 20 + gap 14 + label + 8 + padding 12 + end 5, and a little air. */
private val ItemChrome = 82.dp + 16.dp
private val MarkSize = 24.dp
/** The open menu's card: a shade lighter than the page background, floating with space around it. */
private val RailPanel = Color(0xFF1C1A18)
private val CardInsetX = 6.dp
/** Space inside the card above the first and below the last entry. */
private val CardPadding = 6.dp
private val CardCorner = 18.dp

class RailEntry(val title: String, val icon: ImageVector)

/**
 * Menu on the left: small icons while you browse; with focus it opens as a rounded card floating
 * over the page (space above, below and to the left), with labels, and the page behind dims
 * slightly. Everything follows one animated value, so the menu opens instead of jumping. Right or
 * OK goes into the page.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SideRail(
    entries: List<RailEntry>,
    selected: Int,
    selectedRequester: FocusRequester,
    onFocusEntry: (Int) -> Unit,
    onEnter: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    // Open as wide as the longest label needs (it differs per language), plus icon and padding.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val labelStyle = androidx.tv.material3.LocalTextStyle.current.merge(androidx.compose.ui.text.TextStyle(fontSize = LabelSize, fontWeight = FontWeight.SemiBold))
    val openWidth = remember(entries.map { it.title }, labelStyle, density) {
        val longest = entries.maxOfOrNull { measurer.measure(it.title, labelStyle, maxLines = 1).size.width } ?: 0
        with(density) { longest.toDp() } + ItemChrome
    }.coerceIn(RailWidth + 80.dp, 320.dp)
    val p by animateFloatAsState(if (open) 1f else 0f, tween(300, easing = FastOutSlowInEasing), label = "rail")

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Color.Black.copy(alpha = 0.25f)))
        Column(
            Modifier
                .fillMaxHeight()
                .width(lerp(RailWidth, openWidth, p))
                .drawBehind {
                    // Closed: a soft shade so the icons read over pictures. Open: a solid panel.
                    drawRect(Brush.horizontalGradient(
                        0f to Bg.copy(alpha = 0.8f),
                        0.7f to Bg.copy(alpha = 0.5f),
                        1f to Bg.copy(alpha = 0f),
                    ), alpha = 1f - p)
                }
                .onFocusChanged { open = it.hasFocus; onFocusChange(it.hasFocus) }
                // Always enter on the open page's entry: the page can change without the menu (Home key),
                // so the last focused entry is not necessarily the right one.
                .focusProperties { enter = { selectedRequester } }
                .focusGroup()
                .onPreviewKeyEvent { e ->
                    if (e.key == Key.DirectionRight && e.type == KeyEventType.KeyDown) { onEnter(); true } else false
                }
                .padding(top = 22.dp, bottom = 22.dp),
        ) {
            // The small mark, always in the same place above the icons; the name joins it when the menu opens.
            Row(Modifier.padding(start = (RailWidth - MarkSize) / 2).clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.alike.masal.R.drawable.masal_mark), null,
                    Modifier.size(MarkSize),
                )
                Text("Masal", color = TextMain, fontSize = 19.sp, fontFamily = MasalWordmarkFont, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = 10.dp).graphicsLayer { alpha = p; translationX = (1f - p) * -12f })
            }
            // The entries, centred; the open card is exactly as tall as they are (the mark stays above it).
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            if (p > 0f) {
                                val inset = androidx.compose.ui.geometry.Offset(CardInsetX.toPx(), 0f)
                                val card = androidx.compose.ui.geometry.Size(size.width - inset.x, size.height)
                                val corner = androidx.compose.ui.geometry.CornerRadius(CardCorner.toPx())
                                // A soft shadow under the card, then the card and its fine edge.
                                for (i in 1..3) drawRoundRect(Color.Black, inset + androidx.compose.ui.geometry.Offset(0f, i * 2.dp.toPx()), card,
                                    corner, alpha = p * 0.12f)
                                drawRoundRect(RailPanel, inset, card, corner, alpha = p)
                                drawRoundRect(Line, inset, card, corner, alpha = p,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                            }
                        }
                        .padding(vertical = CardPadding),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    entries.forEachIndexed { i, e ->
                        RailItem(e, i == selected, { p }, { onFocusEntry(i) }, { onFocusEntry(i); onEnter() },
                            if (i == selected) Modifier.focusRequester(selectedRequester) else Modifier)
                    }
                }
            }
        }
    }
}

@Composable
private fun RailItem(entry: RailEntry, selected: Boolean, progress: () -> Float, onFocus: () -> Unit, onClick: () -> Unit, modifier: Modifier) {
    // The open page: amber icon and label (and an amber bar at the edge while closed). Focus: a soft light field
    // (no glow, no zoom), so moving through the menu stays calm.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 5.dp)) {
        Box(Modifier.width(3.dp).height(18.dp).graphicsLayer { alpha = if (selected) 1f - progress() else 0f }
            .background(Accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (selected) Accent else TextDim,
                focusedContainerColor = Color.White.copy(alpha = 0.12f),
                focusedContentColor = if (selected) Accent else TextMain,
                // Pressing looks like focus (the default pressed colours flash dark before the page opens).
                pressedContainerColor = Color.White.copy(alpha = 0.12f),
                pressedContentColor = if (selected) Accent else TextMain,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.Transparent, 0.dp)),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                Icon(entry.icon, contentDescription = entry.title, modifier = Modifier.size(20.dp))
                Text(entry.title, fontSize = LabelSize, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp)
                        .graphicsLayer { val p = progress(); alpha = p; translationX = (1f - p) * -16f })
            }
        }
    }
}

// ---- line icons (24 x 24, drawn with a 2 px stroke, tinted by Icon) ----

private fun lineIcon(name: String, block: PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        PathData(block), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

object MasalIcons {
    val Home = lineIcon("home") {
        moveTo(3f, 11f); lineTo(12f, 4f); lineTo(21f, 11f)
        moveTo(5.5f, 9.5f); verticalLineTo(20f); horizontalLineTo(18.5f); verticalLineTo(9.5f)
        moveTo(10f, 20f); verticalLineTo(14.5f); horizontalLineTo(14f); verticalLineTo(20f)
    }
    val Tv = lineIcon("tv") {
        moveTo(3f, 7f); horizontalLineTo(21f); verticalLineTo(18f); horizontalLineTo(3f); close()
        moveTo(8f, 21f); horizontalLineTo(16f)
        moveTo(8.5f, 3f); lineTo(12f, 7f); lineTo(15.5f, 3f)
    }
    val Film = lineIcon("film") {
        moveTo(4f, 4f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(8f, 4f); verticalLineTo(20f); moveTo(16f, 4f); verticalLineTo(20f)
        moveTo(4f, 9f); horizontalLineTo(8f); moveTo(4f, 15f); horizontalLineTo(8f)
        moveTo(16f, 9f); horizontalLineTo(20f); moveTo(16f, 15f); horizontalLineTo(20f)
    }
    val Series = lineIcon("series") {
        moveTo(3f, 8f); horizontalLineTo(17f); verticalLineTo(20f); horizontalLineTo(3f); close()
        moveTo(7f, 4.5f); horizontalLineTo(20.5f); verticalLineTo(16f)
        moveTo(8.5f, 11f); lineTo(12.5f, 14f); lineTo(8.5f, 17f); close()
    }
    val Apps = lineIcon("apps") {
        moveTo(4f, 4f); horizontalLineTo(10f); verticalLineTo(10f); horizontalLineTo(4f); close()
        moveTo(14f, 4f); horizontalLineTo(20f); verticalLineTo(10f); horizontalLineTo(14f); close()
        moveTo(4f, 14f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(4f); close()
        moveTo(14f, 14f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(14f); close()
    }
    val Settings get() = Icons.Outlined.Settings
    val Search get() = Icons.Outlined.Search
}
