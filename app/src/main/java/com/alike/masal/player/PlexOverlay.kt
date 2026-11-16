package com.alike.masal.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alike.masal.data.PlexItem
import com.alike.masal.ui.Accent
import com.alike.masal.ui.Bg
import com.alike.masal.ui.OnAccent
import com.alike.masal.ui.TextDim
import com.alike.masal.ui.TextMain
import com.alike.masal.ui.TitleArt
import com.alike.masal.ui.formatClock
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line in a side panel (a subtitle, an audio track, a language, a search result). */
class PanelOption(val label: String, val detail: String = "", val selected: Boolean = false, val action: () -> Unit)

/**
 * A side panel. [back] is where Back leads (a settings choice back to the settings list; null
 * closes the panel); [focus] is the line that gets focus (else the selected one, else the first).
 */
class Panel(
    val title: String,
    val options: List<PanelOption> = emptyList(),
    val note: String? = null,
    val back: (() -> Panel)? = null,
    val focus: Int? = null,
)

/** What the Plex player shows; owned by PlayerActivity. */
class PlexUiState {
    var item by mutableStateOf<PlexItem?>(null)
    var controls by mutableStateOf(false)
    /** Which control gets focus when the bar appears: "play" or "seek". */
    var focusTarget by mutableStateOf("play")
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var position by mutableLongStateOf(0)
    var buffered by mutableLongStateOf(0)
    var duration by mutableLongStateOf(0)
    /** Position being scrubbed to; the player jumps there once the remote rests. */
    var seekTarget by mutableStateOf<Long?>(null)
    var panel by mutableStateOf<Panel?>(null)
    var subtitleLabel by mutableStateOf("Off")
    var audioLabel by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    /** Bumped to move focus back into the bar (after a panel closes). */
    var focusTick by mutableLongStateOf(0)
    /** Skip lengths in seconds, from the settings. */
    var skipBack by mutableIntStateOf(10)
    var skipForward by mutableIntStateOf(30)
}

class PlexActions(
    val playPause: () -> Unit,
    val seekBy: (Long) -> Unit,
    val subtitles: () -> Unit,
    val audio: () -> Unit,
    val touch: () -> Unit,
    val settings: () -> Unit = {},
)

@Composable
fun PlexOverlay(state: PlexUiState, actions: PlexActions) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(state.controls && state.panel == null, enter = fadeIn(tween(250)), exit = fadeOut(tween(300))) {
            Controls(state, actions)
        }
        AnimatedVisibility(
            state.panel != null,
            enter = slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            state.panel?.let { androidx.compose.runtime.key(it) { SidePanel(it, actions.touch) } }
        }
        state.message?.let { msg ->
            LaunchedEffect(msg) { delay(3_500); if (state.message == msg) state.message = null }
            Text(msg, color = TextMain, fontSize = 14.sp, modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                .background(Bg.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun Controls(state: PlexUiState, actions: PlexActions) {
    val item = state.item
    val play = remember { FocusRequester() }
    val seek = remember { FocusRequester() }
    LaunchedEffect(state.focusTick) {
        delay(30)
        runCatching { if (state.focusTarget == "seek") seek.requestFocus() else play.requestFocus() }
    }
    val clock = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Box(Modifier.fillMaxSize()) {
        // Top: title artwork, episode, clock.
        Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
        Column(Modifier.padding(start = 48.dp, top = 30.dp)) {
            if (item != null) {
                TitleArt(item, width = 300.dp, height = 70.dp, textSize = 28)
                if (item.type == "episode") {
                    Text("S${item.parentIndex} · E${item.index}   ${item.episodeTitle.orEmpty()}", color = TextMain, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        TickingText({ clock.format(Date(it)) }, Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 48.dp),
            color = TextMain, fontSize = 18, bold = true)

        // Bottom: timeline and buttons.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(230.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 48.dp, vertical = 28.dp)) {
            val pos = state.seekTarget ?: state.position
            val dur = state.duration.coerceAtLeast(1)
            SeekBar(pos, state.buffered, dur, seek, actions)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(formatClock(pos), color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                val left = (dur - pos).coerceAtLeast(0)
                TickingText({ "Ends at ${clock.format(Date(it + left))}" }, Modifier, color = TextDim, fontSize = 13)
                Spacer(Modifier.width(16.dp))
                Text("-" + formatClock(left), color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(PlayerIcons.Back, "${state.skipBack}", actions.touch, onClick = { actions.seekBy(-state.skipBack * 1000L) })
                Spacer(Modifier.width(14.dp))
                RoundButton(if (state.playing) PlayerIcons.Pause else Icons.Filled.PlayArrow, null, actions.touch,
                    big = true, modifier = Modifier.focusRequester(play), onClick = actions.playPause)
                Spacer(Modifier.width(14.dp))
                RoundButton(PlayerIcons.Forward, "${state.skipForward}", actions.touch, onClick = { actions.seekBy(state.skipForward * 1000L) })
                Spacer(Modifier.weight(1f))
                PillButton(PlayerIcons.Subtitles, state.subtitleLabel, actions.touch, actions.subtitles)
                Spacer(Modifier.width(12.dp))
                PillButton(PlayerIcons.Audio, state.audioLabel.ifBlank { "Audio" }, actions.touch, actions.audio)
                Spacer(Modifier.width(12.dp))
                PillButton(Icons.Filled.Settings, "Settings", actions.touch, actions.settings)
            }
        }
    }
}

@Composable
private fun SeekBar(pos: Long, buffered: Long, dur: Long, requester: FocusRequester, actions: PlexActions) {
    var focused by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(0) }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(22.dp)
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.key != Key.DirectionLeft && e.key != Key.DirectionRight) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyUp) { repeat = 0; return@onPreviewKeyEvent true }
                // Holding the button speeds up: 10 s, then 30 s, then 1 min steps.
                repeat++
                val step = when { repeat > 12 -> 60_000L; repeat > 4 -> 30_000L; else -> 10_000L }
                actions.seekBy(if (e.key == Key.DirectionLeft) -step else step)
                true
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val h = if (focused) 6.dp else 4.dp
        val f = (pos.toFloat() / dur).coerceIn(0f, 1f)
        val b = (buffered.toFloat() / dur).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(h).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50)))
        Box(Modifier.fillMaxWidth(b).height(h).background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(50)))
        Box(Modifier.fillMaxWidth(f).height(h).background(Accent, RoundedCornerShape(50)))
        if (focused) {
            Box(Modifier.offset(x = maxWidth * f - 8.dp).size(16.dp).background(TextMain, CircleShape))
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    badge: String?,
    touch: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    onClick: () -> Unit,
) {
    val size = if (big) 56.dp else 44.dp
    Surface(
        onClick = { touch(); onClick() },
        modifier = modifier.size(size).onFocusChanged { if (it.isFocused) touch() },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = if (big) 0.18f else 0.1f), contentColor = TextMain,
            focusedContainerColor = TextMain, focusedContentColor = OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Accent.copy(alpha = 0.5f), 12.dp)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (big) 30.dp else 26.dp))
            if (badge != null) Text(badge, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, touch: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = { touch(); onClick() },
        modifier = Modifier.height(40.dp).onFocusChanged { if (it.isFocused) touch() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f), contentColor = TextMain,
            focusedContainerColor = TextMain, focusedContentColor = OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp).widthIn(max = 200.dp))
        }
    }
}

@Composable
private fun SidePanel(panel: Panel, touch: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(panel) { delay(60); runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxHeight().width(380.dp)
            .background(Brush.horizontalGradient(0f to Color.Transparent, 0.08f to Bg.copy(alpha = 0.94f), 1f to Bg.copy(alpha = 0.98f)))
            .padding(start = 40.dp, end = 28.dp, top = 36.dp),
    ) {
        Text(panel.title, color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        panel.note?.let { Text(it, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        // Start scrolled to the selected option: with many tracks it may lie beyond the first screen,
        // and focus can only be requested on a composed row.
        val selected = (panel.focus ?: panel.options.indexOfFirst { it.selected }).coerceAtLeast(0)
        val listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (selected - 2).coerceAtLeast(0))
        LazyColumn(state = listState, contentPadding = PaddingValues(top = 14.dp, bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(panel.options) { i, o ->
                val target = i == selected
                Surface(
                    onClick = { touch(); o.action() },
                    modifier = Modifier.fillMaxWidth().then(if (target) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { if (it.isFocused) touch() },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent, contentColor = TextMain,
                        focusedContainerColor = TextMain, focusedContentColor = OnAccent,
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(26.dp)) {
                            if (o.selected) Icon(Icons.Filled.Check, contentDescription = "selected", modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(o.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (o.detail.isNotBlank()) Text(o.detail, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// ---- icons (24 x 24) ----

private fun icon(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        PathData(block),
        fill = if (filled) SolidColor(Color.Black) else null,
        stroke = if (filled) null else SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

object PlayerIcons {
    val Pause = icon("pause", filled = true) {
        moveTo(6f, 4f); horizontalLineTo(10f); verticalLineTo(20f); horizontalLineTo(6f); close()
        moveTo(14f, 4f); horizontalLineTo(18f); verticalLineTo(20f); horizontalLineTo(14f); close()
    }
    /** Circular arrow anticlockwise (skip back); the seconds are drawn as text inside. */
    val Back = icon("back") {
        moveTo(5f, 12f); arcTo(7f, 7f, 0f, true, false, 8.5f, 6f)
        moveTo(8.5f, 2.5f); lineTo(8.5f, 6f); lineTo(5f, 6f)
    }
    val Forward = icon("forward") {
        moveTo(19f, 12f); arcTo(7f, 7f, 0f, true, true, 15.5f, 6f)
        moveTo(15.5f, 2.5f); lineTo(15.5f, 6f); lineTo(19f, 6f)
    }
    val Subtitles = icon("subtitles") {
        moveTo(3f, 5f); horizontalLineTo(21f); verticalLineTo(19f); horizontalLineTo(3f); close()
        moveTo(7f, 12f); horizontalLineTo(11f); moveTo(13f, 12f); horizontalLineTo(17f)
        moveTo(7f, 15.5f); horizontalLineTo(14f)
    }
    val Audio = icon("audio") {
        moveTo(4f, 9.5f); horizontalLineTo(8f); lineTo(13f, 5f); verticalLineTo(19f); lineTo(8f, 14.5f); horizontalLineTo(4f); close()
        moveTo(16.5f, 9f); quadTo(18.5f, 12f, 16.5f, 15f)
        moveTo(19f, 6.5f); quadTo(23f, 12f, 19f, 17.5f)
    }
}

/**
 * Text built from the current time, refreshed on every full second. Only this text recomposes,
 * so the clock and "Ends at" (now + time left) keep moving while paused without redrawing the overlay.
 */
@Composable
private fun TickingText(text: (Long) -> String, modifier: Modifier, color: Color, fontSize: Int, bold: Boolean = false) {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { delay(1000 - System.currentTimeMillis() % 1000); value = System.currentTimeMillis() }
    }
    Text(text(now), color = color, fontSize = fontSize.sp, modifier = modifier,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
}
