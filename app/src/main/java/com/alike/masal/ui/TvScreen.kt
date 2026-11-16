package com.alike.masal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alike.masal.MasalViewModel
import com.alike.masal.R
import com.alike.masal.data.Channel
import com.alike.masal.data.nowAndNext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Internal keys of the two own lists (saved selection, focus keys); shown through their string resources.
private const val RECENT = "Last watched"
private const val FAVOURITES = "Favourites"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvScreen(vm: MasalViewModel, autoFocus: Boolean, onPlay: (String, List<Channel>, Int) -> Unit) {
    val channels by vm.channels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val recentKeys by vm.recentChannels.collectAsState()
    val epg by vm.epg.collectAsState()
    val loading by vm.loadingChannels.collectAsState()
    val recentLabel = stringResource(R.string.tv_last_watched)
    val favouritesLabel = stringResource(R.string.tv_favourites)
    val label = { name: String? ->
        when (name) {
            RECENT -> recentLabel
            FAVOURITES -> favouritesLabel
            else -> name.orEmpty()
        }
    }

    val groups = remember(channels, favorites, recentKeys) {
        val byKey = channels.associateBy { it.key }
        val recent = recentKeys.mapNotNull { byKey[it] }
        val favs = channels.filter { it.key in favorites }
        buildList {
            if (recent.isNotEmpty()) add(RECENT to recent)
            if (favs.isNotEmpty()) add(FAVOURITES to favs)
            addAll(channels.groupBy { it.group }.toList())
        }
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = groups.firstOrNull { it.first == selected } ?: groups.firstOrNull()
    var focused by remember { mutableStateOf<Channel?>(null) }
    val focus = rememberRestorableFocus(autoFocus, groups.isNotEmpty())
    focus.defaultKey = "cat:" + groups.firstOrNull()?.first

    val listState = rememberLazyListState()
    LaunchedEffect(current?.first) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }

    // OK on a category jumps into its channels; Back in the channels returns to the category.
    val categoryFocus = remember { FocusRequester() }
    val channelFocus = remember { FocusRequester() }
    var enterList by remember { mutableStateOf(0) }
    var channelsFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(enterList) {
        if (enterList == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        delay(32)
        runCatching { channelFocus.requestFocus() }.onFailure { Log.w("MasalFocus", "enter channels: $it") }
        delay(48)
        if (!channelsFocused) {
            Log.w("MasalFocus", "enter channels: request ignored, moving right")
            focusManager.moveFocus(FocusDirection.Right)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(NavHeight))
        if (groups.isEmpty()) {
            Text(
                stringResource(if (loading) R.string.tv_loading_channels else R.string.tv_no_channels),
                color = TextDim, modifier = Modifier.padding(start = EdgePadding, top = 24.dp),
            )
            return
        }
        Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
            // Categories
            LazyColumn(
                Modifier.width(214.dp).fillMaxHeight(),
                contentPadding = PaddingValues(start = EdgePadding - 10.dp, end = 10.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(groups, key = { "cat:" + it.first }) { (name, list) ->
                    CategoryItem(
                        name = label(name),
                        count = list.size,
                        selected = name == current?.first,
                        onFocus = { selected = name },
                        onClick = { selected = name; enterList++ },
                        modifier = focus.modifier("cat:$name")
                            .then(if (name == current?.first) Modifier.focusRequester(categoryFocus) else Modifier),
                    )
                }
            }

            Column(Modifier.weight(1f).padding(end = EdgePadding)) {
                // Preview + guide for the focused channel
                Row(Modifier.fillMaxWidth().height(196.dp)) {
                    Box(Modifier.fillMaxHeight().width(348.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                        if (focused != null) {
                            LivePreview(focused, Modifier.fillMaxSize())
                        } else {
                            Text(stringResource(R.string.tv_preview_hint), color = TextDim, fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.Center))
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    focused?.let { HeroLive(it, it.epgId?.let { id -> epg[id] }, Modifier.fillMaxHeight()) }
                }
                Row(Modifier.padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label(current?.first), color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    val count = current?.second?.size ?: 0
                    Text("  " + pluralStringResource(R.plurals.tv_channels_count, count, count) + "   " + stringResource(R.string.tv_favourite_hint),
                        color = TextDim, fontSize = 11.sp)
                }
                val list = current?.second.orEmpty()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                        .onFocusChanged { channelsFocused = it.hasFocus }
                        .focusRequester(channelFocus)
                        .focusGroup()
                        .onPreviewKeyEvent { e ->
                        if (e.key != Key.Back) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyUp) runCatching { categoryFocus.requestFocus() }
                        true
                    },
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Keyed by position: "Last watched" reorders after playing, and a moved key would drop focus.
                    itemsIndexed(list, key = { i, _ -> i }) { i, ch ->
                        ChannelRow(
                            number = i + 1,
                            channel = ch,
                            epgList = ch.epgId?.let { epg[it] },
                            favorite = ch.key in favorites,
                            onFocus = { focused = ch },
                            onClick = { onPlay(label(current?.first), list, i) },
                            onLongClick = { vm.toggleFavorite(ch) },
                            modifier = focus.modifier("ch:${ch.key}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(name: String, count: Int, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PanelFocus else Color.Transparent,
            contentColor = if (selected) TextMain else TextDim,
            focusedContainerColor = TextMain,
            focusedContentColor = Bg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(16.dp).background(if (selected) Accent else Color.Transparent))
            Text(name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
            Text("$count", fontSize = 11.sp)
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    channel: Channel,
    epgList: List<com.alike.masal.data.Programme>?,
    favorite: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    val (now, next) = epgList?.nowAndNext() ?: (null to null)
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    // The star is a separate button (Right from the row) because TV remotes over HDMI-CEC
    // often cannot send a long press.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Panel, focusedContainerColor = PanelFocus),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Accent), shape = RoundedCornerShape(6.dp)),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$number", color = TextDim, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                Box(Modifier.size(width = 62.dp, height = 35.dp).background(Bg, RoundedCornerShape(4.dp)).padding(4.dp),
                    contentAlignment = Alignment.Center) {
                    NetImage(channel.logo, channel.name, Modifier.fillMaxSize(), ContentScale.Fit)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(channel.name, color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (favorite) Box(Modifier.padding(start = 6.dp).size(6.dp).background(Accent, RoundedCornerShape(3.dp)))
                    }
                    Text(now?.title ?: stringResource(R.string.tv_no_guide), color = TextDim, fontSize = 11.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                if (now != null) {
                    Column(Modifier.width(150.dp), horizontalAlignment = Alignment.End) {
                        Text("${fmt.format(Date(now.start))}–${fmt.format(Date(now.stop))}", color = TextDim, fontSize = 11.sp)
                        ProgrammeBar(now, Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(2.dp)), track = Line)
                        if (next != null) Text(stringResource(R.string.tv_next, next.title), color = TextDim, fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        FavouriteButton(favorite, onLongClick)
    }
}

@Composable
private fun FavouriteButton(favorite: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.padding(start = 8.dp).size(34.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (favorite) Accent else Line,
            focusedContainerColor = TextMain,
            focusedContentColor = if (favorite) Accent else OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Accent.copy(alpha = 0.5f), 10.dp)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.tv.material3.Icon(Icons.Filled.Star, contentDescription = stringResource(if (favorite) R.string.tv_remove_favourite else R.string.tv_add_favourite),
                modifier = Modifier.size(18.dp))
        }
    }
}
