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
private val RailOpenWidth = 236.dp

class RailEntry(val title: String, val icon: ImageVector)

/**
 * Menu on the left: small icons while you browse, widening with labels and a
 * dimmed screen as soon as it has focus. Everything follows one animated value,
 * so the menu slides open instead of jumping. Right or OK goes into the page.
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
    val p by animateFloatAsState(if (open) 1f else 0f, tween(300, easing = FastOutSlowInEasing), label = "rail")
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) { clock = fmt.format(Date()); delay(15_000) }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Color.Black.copy(alpha = 0.55f)))
        Column(
            Modifier
                .fillMaxHeight()
                .width(lerp(RailWidth, RailOpenWidth, p))
                .drawBehind {
                    drawRect(Brush.horizontalGradient(
                        0f to Bg.copy(alpha = 0.8f + 0.19f * p),
                        0.7f to Bg.copy(alpha = 0.5f + 0.45f * p),
                        1f to Bg.copy(alpha = 0f),
                    ))
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
            Row(Modifier.padding(start = 14.dp).clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                Text(clock, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                Row(
                    Modifier.padding(start = 12.dp).graphicsLayer { alpha = p; translationX = (1f - p) * -24f },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(com.alike.masal.R.drawable.masal_mark), null,
                        Modifier.size(30.dp),
                    )
                    Text("Masal", color = TextMain, fontSize = 22.sp, fontFamily = MasalWordmarkFont, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                entries.forEachIndexed { i, e ->
                    RailItem(e, i == selected, { p }, { onFocusEntry(i) }, { onFocusEntry(i); onEnter() },
                        if (i == selected) Modifier.focusRequester(selectedRequester) else Modifier)
                }
            }
        }
    }
}

@Composable
private fun RailItem(entry: RailEntry, selected: Boolean, progress: () -> Float, onFocus: () -> Unit, onClick: () -> Unit, modifier: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
        Box(Modifier.width(3.dp).height(18.dp).graphicsLayer { alpha = if (selected) 1f - progress() else 0f }
            .background(Accent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                contentColor = if (selected) Accent else TextDim,
                focusedContainerColor = TextMain,
                focusedContentColor = OnAccent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Accent.copy(alpha = 0.45f), 10.dp)),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).clipToBounds(), verticalAlignment = Alignment.CenterVertically) {
                Icon(entry.icon, contentDescription = entry.title, modifier = Modifier.size(20.dp))
                Text(entry.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
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
