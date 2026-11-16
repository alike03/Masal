package com.alike.masal.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alike.masal.data.Channel
import com.alike.masal.data.Programme
import com.alike.masal.data.nowAndNext
import com.alike.masal.ui.Accent
import com.alike.masal.ui.Bg
import com.alike.masal.ui.Chip
import com.alike.masal.ui.Line
import com.alike.masal.ui.NetImage
import com.alike.masal.ui.Panel
import com.alike.masal.ui.PanelFocus
import com.alike.masal.ui.ProgrammeBar
import com.alike.masal.ui.TextDim
import com.alike.masal.ui.TextMain
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** UI state of the live player, owned by PlayerActivity. */
class LiveState {
    var groups by mutableStateOf<List<Pair<String, List<Channel>>>>(emptyList())
    var listName by mutableStateOf("")
    var channels by mutableStateOf<List<Channel>>(emptyList())
    var index by mutableIntStateOf(0)
    var epg by mutableStateOf<Map<String, List<Programme>>>(emptyMap())
    var infoVisible by mutableStateOf(false)
    var listVisible by mutableStateOf(false)
    var digits by mutableStateOf("")

    val current get() = channels.getOrNull(index)
}

@Composable
fun LiveOverlay(state: LiveState, onPick: (name: String, list: List<Channel>, index: Int) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (state.infoVisible && !state.listVisible) {
            state.current?.let { InfoBanner(state.index + 1, it, it.epgId?.let { id -> state.epg[id] }, Modifier.align(Alignment.BottomCenter)) }
        }
        if (state.digits.isNotEmpty()) {
            Text(
                state.digits, color = TextMain, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                    .background(Bg.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }
        if (state.listVisible) ChannelPanel(state, onPick)
    }
}

@Composable
private fun InfoBanner(number: Int, ch: Channel, programmes: List<Programme>?, modifier: Modifier) {
    val (now, next) = programmes?.nowAndNext() ?: (null to null)
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Bg.copy(alpha = 0.95f))))
            .padding(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 112.dp, height = 63.dp).background(Panel, RoundedCornerShape(6.dp)).padding(8.dp),
                contentAlignment = Alignment.Center) {
                NetImage(ch.logo, ch.name, Modifier.fillMaxSize(), ContentScale.Fit)
            }
            Column(Modifier.padding(start = 18.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("$number", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(ch.name, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Chip("LIVE", highlight = true)
                }
                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Text("${fmt.format(Date(now.start))}–${fmt.format(Date(now.stop))}", color = TextDim, fontSize = 13.sp)
                        Text("  ${now.title}", color = TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ProgrammeBar(now, Modifier.padding(top = 6.dp).width(320.dp).clip(RoundedCornerShape(2.dp)), track = Line)
                } else {
                    Text(ch.group, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (next != null) {
                    Text("Next ${fmt.format(Date(next.start))}  ${next.title}", color = TextDim, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Text("OK  channel list    ◀ ▶ ▲ ▼  switch", color = TextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ChannelPanel(state: LiveState, onPick: (String, List<Channel>, Int) -> Unit) {
    val groups = state.groups
    var gi by remember { mutableIntStateOf(groups.indexOfFirst { it.first == state.listName }.coerceAtLeast(0)) }
    val (name, list) = groups.getOrNull(gi) ?: (state.listName to state.channels)
    val isCurrentList = name == state.listName
    val start = if (isCurrentList) state.index else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val focusTarget = remember { FocusRequester() }
    LaunchedEffect(gi) {
        listState.scrollToItem((start - 3).coerceAtLeast(0))
        delay(50)
        runCatching { focusTarget.requestFocus() }
    }

    Box(
        Modifier.fillMaxHeight().width(380.dp)
            .background(Brush.horizontalGradient(0f to Bg.copy(alpha = 0.97f), 0.85f to Bg.copy(alpha = 0.9f), 1f to Color.Transparent))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || groups.size < 2) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { gi = (gi - 1 + groups.size) % groups.size; true }
                    Key.DirectionRight -> { gi = (gi + 1) % groups.size; true }
                    else -> false
                }
            },
    ) {
        Column(Modifier.padding(start = 28.dp, end = 36.dp, top = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = TextDim, fontSize = 22.sp)
                Text(name, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                Text("›", color = TextDim, fontSize = 22.sp)
            }
            Text("${list.size} channels   ◀ ▶ category", color = TextDim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(list, key = { i, c -> "$i|${c.key}" }) { i, ch ->
                    val playing = isCurrentList && i == state.index
                    val now = ch.epgId?.let { state.epg[it] }?.nowAndNext()?.first
                    Surface(
                        onClick = { onPick(name, list, i) },
                        modifier = Modifier.fillMaxWidth().then(if (i == start) Modifier.focusRequester(focusTarget) else Modifier),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (playing) PanelFocus else Color.Transparent,
                            focusedContainerColor = TextMain,
                            contentColor = TextMain,
                            focusedContentColor = Bg,
                        ),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", fontSize = 11.sp, modifier = Modifier.width(28.dp),
                                color = if (playing) Accent else Color.Unspecified)
                            Box(Modifier.size(width = 50.dp, height = 28.dp).background(Panel, RoundedCornerShape(3.dp)).padding(3.dp),
                                contentAlignment = Alignment.Center) {
                                NetImage(ch.logo, ch.name, Modifier.fillMaxSize(), ContentScale.Fit)
                            }
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(ch.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (now != null) Text(now.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
