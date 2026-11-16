package com.alike.masal.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.alike.masal.Graph
import com.alike.masal.R
import com.alike.masal.data.Channel
import com.alike.masal.data.PlexItem
import com.alike.masal.data.Programme
import com.alike.masal.data.nowAndNext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One horizontal row on a rows page; [height] covers its title and the room a focused card needs. */
class RowSpec(val key: String, val height: Dp, val content: @Composable () -> Unit)

/** Which row has focus; lets a tab bar above send focus straight back to it. */
class RowsState(initialKey: String? = null) {
    var focusedKey by mutableStateOf(initialKey)
    internal val requesters = HashMap<String, FocusRequester>()
    /** Row keys in screen order, updated by [FoldingRows]. */
    internal var order: List<String> = emptyList()

    /** Focuses the focused row, else the first one. False when no row could take focus. */
    fun focusCurrent(): Boolean {
        val key = focusedKey?.takeIf { it in order } ?: order.firstOrNull() ?: return false
        val r = requesters[key] ?: return false
        return runCatching { r.requestFocus() }.isSuccess
    }
}

/** A [RowsState] that keeps the focused row across Back, so the restored card's row is composed again. */
@Composable
fun rememberRowsState(): RowsState =
    rememberSaveable(saver = Saver({ it.focusedKey.orEmpty() }, { RowsState(it.ifEmpty { null }) })) { RowsState() }

/** Card sizes on rows pages (Plex proportions on a 960 x 540 dp screen). */
val RowPosterWidth = 112.dp
val PosterRowHeight = 228.dp
val BannerRowHeight = 168.dp

/**
 * Plex-style rows, opening like an accordion: the focused row sits at the top with its header
 * (the hero text) open above its title, and the next row peeks in at the bottom. Moving down,
 * the previous row slides straight up and out while its header closes, and the next row's
 * header opens above its title. Nothing fades or tilts. A critically damped spring drives
 * everything, so fast key repeats stay smooth instead of queueing up.
 */
@Composable
fun FoldingRows(
    rows: List<RowSpec>,
    headerHeight: Dp,
    state: RowsState = rememberRowsState(),
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    state.order = rows.map { it.key }
    val f = rows.indexOfFirst { it.key == state.focusedKey }.coerceAtLeast(0)
    val motion = spring<Dp>(dampingRatio = 1f, stiffness = 320f)
    var acc = 0.dp
    val starts = rows.map { r -> acc.also { acc += r.height } } // row tops with every header closed
    val scroll by animateDpAsState(starts.getOrElse(f) { 0.dp }, motion, label = "rows")
    val open = rows.mapIndexed { i, r ->
        key(r.key) { animateDpAsState(if (i == f) headerHeight else 0.dp, motion, label = "open").value }
    }
    // Top of each row's block (header slot + row), relative to the top of the screen.
    var openAbove = 0.dp
    val blockTops = rows.indices.map { i -> (starts[i] + openAbove - scroll).also { openAbove += open[i] } }

    Box(modifier.fillMaxSize()) {
        // No rows yet (loading, empty library): the header alone, e.g. a status line.
        if (rows.isEmpty()) Box(Modifier.fillMaxWidth().height(headerHeight)) { header() }
        if (rows.isNotEmpty()) {
            // The header lives in the focused row's slot; the slot grows from the row title upwards.
            Layout(
                content = header,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, blockTops[f].roundToPx()) }
                    .height(open[f])
                    .clipToBounds(),
            ) { measurables, constraints ->
                val h = headerHeight.roundToPx()
                val placeables = measurables.map { it.measure(constraints.copy(minHeight = h, maxHeight = h)) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeables.forEach { it.place(0, constraints.maxHeight - h) }
                }
            }
        }
        rows.forEachIndexed { i, row ->
            key(row.key) {
                val requester = remember { FocusRequester() }
                state.requesters[row.key] = requester
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(row.height)
                        .offset { IntOffset(0, (blockTops[i] + open[i]).roundToPx()) }
                        .onFocusChanged { if (it.hasFocus) state.focusedKey = row.key }
                        .focusRequester(requester)
                        .focusGroup(),
                ) {
                    // Only rows near the focused one are composed; the rest are empty slots of the same height.
                    if (i in f - 2..f + 3) row.content()
                }
            }
        }
    }
}

@Composable
fun RowTitle(title: String) {
    Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.padding(start = EdgePadding, bottom = 2.dp))
}

/** The title as Plex shows it: its logo artwork when the library has one, otherwise large text. */
@Composable
fun TitleArt(item: PlexItem, width: Dp = 380.dp, height: Dp = 92.dp, textSize: Int = 36) {
    val logo = Graph.plex.logoImage(item.logo)
    var failed by remember(logo) { mutableStateOf(logo == null) }
    if (!failed) {
        AsyncImage(
            model = logo, contentDescription = item.title, contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart, onError = { failed = true },
            modifier = Modifier.size(width, height),
        )
    } else {
        Text(item.title, color = TextMain, fontSize = textSize.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            lineHeight = (textSize + 4).sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(width + 80.dp))
    }
}

fun releaseDate(iso: String?): String? = runCatching {
    val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso!!)!!
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(d)
}.getOrNull()

/** "S1 • E5   27 Oct 2026   45m   [TV-14]" or "2008   2h 6m   Action, Adventure   [12]". */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MetaLine(item: PlexItem, modifier: Modifier = Modifier) {
    val parts = buildList {
        when (item.type) {
            "episode" -> { add(stringResource(R.string.common_season_episode, item.parentIndex, item.index)); releaseDate(item.released)?.let { add(it) } }
            "season" -> add(item.subtitle)
            else -> item.year?.let { add("$it") }
        }
        if (item.duration > 0 && item.type != "show") add(formatDuration(item.duration))
        if (item.type == "show" && item.childCount > 0) add(pluralStringResource(R.plurals.common_seasons, item.childCount, item.childCount))
        if (item.type != "episode" && item.genres.isNotEmpty()) add(item.genres.take(2).joinToString(", "))
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        parts.forEach { Text(it, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
        item.contentRating?.let {
            Text(it, color = TextMain, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.border(1.5.dp, TextMain.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
}

/** Hero text for a Plex title, laid out like Plex: logo, episode title, facts, summary, cast. */
@Composable
fun HeroPlex(item: PlexItem, modifier: Modifier = Modifier, summaryLines: Int = 2, logoHeight: Dp = 70.dp) {
    Column(modifier.width(430.dp)) {
        TitleArt(item, width = 300.dp, height = logoHeight, textSize = 30)
        if (item.type == "episode" && !item.episodeTitle.isNullOrBlank()) {
            Text(item.episodeTitle, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        MetaLine(item, Modifier.padding(top = if (item.type == "episode") 4.dp else 8.dp))
        item.summary?.let {
            Text(it, color = TextMain.copy(alpha = 0.92f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = summaryLines,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        if (item.cast.isNotEmpty()) {
            Text(item.cast.joinToString(", "), color = TextDim, fontSize = 11.5.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Hero for a live channel: its logo, what is on now and next. */
@Composable
fun HeroChannel(channel: Channel, programmes: List<Programme>?, modifier: Modifier = Modifier) {
    val (now, next) = programmes?.nowAndNext() ?: (null to null)
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(modifier.width(430.dp)) {
        if (channel.logo != null) {
            NetImage(channel.logo, channel.name, Modifier.size(160.dp, 60.dp), ContentScale.Fit)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp)) {
            Chip(stringResource(R.string.common_live), highlight = true)
            Text(channel.name, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        if (now != null) {
            Text(now.title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("${fmt.format(Date(now.start))}–${fmt.format(Date(now.stop))}", color = TextMain, fontSize = 13.sp)
                ProgrammeBar(now, Modifier.padding(start = 12.dp).width(140.dp), track = Line)
            }
            now.desc?.let {
                Text(it, color = TextMain.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            if (next != null) {
                Text(stringResource(R.string.common_next, fmt.format(Date(next.start)), next.title), color = TextDim, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
