package com.alike.masal.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import com.alike.masal.data.nowAndNext
import com.alike.masal.data.foldText
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.MasalViewModel
import com.alike.masal.data.Channel
import com.alike.masal.data.PlexHub
import com.alike.masal.data.PlexItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Search over Plex (all libraries, through Plex's own search) and the IPTV channel names.
 * OK on the field opens the TV keyboard (with voice input on the Shield); results appear
 * while typing, one row per kind, and Down moves from the field to the results.
 */
@Composable
fun SearchScreen(
    vm: MasalViewModel,
    autoFocus: Boolean,
    onOpenItem: (PlexItem) -> Unit,
    onPlayChannel: (String, List<Channel>, Int) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val channels by vm.channels.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val epg by vm.epg.collectAsState()

    // Plex results for [plexQuery]. Kept while the next query is typed (no flicker) and kept in
    // the view model, so coming back from a title shows them at once and focus can return to the card.
    var plexQuery by remember { mutableStateOf(vm.lastSearch?.first) }
    var plex by remember { mutableStateOf(vm.lastSearch?.second) }
    var submitted by remember { mutableIntStateOf(0) } // Search key on the TV keyboard: no debounce
    var focusResults by remember { mutableStateOf(false) }
    LaunchedEffect(q, submitted) {
        if (q.length < 2 || !Graph.plex.configured) { plex = null; plexQuery = null; return@LaunchedEffect }
        if (q == plexQuery && plex?.isSuccess == true) return@LaunchedEffect
        if (!focusResults) delay(400) // wait until typing pauses
        val result = withContext(Dispatchers.IO) { runCatching { Graph.plex.search(q) } }
        plex = result; plexQuery = q
        if (result.isSuccess) vm.lastSearch = q to result
    }

    // Channel names are folded once per channel list, off the main thread (lists can hold 30 000 entries).
    val index by produceState(emptyList<Pair<Channel, String>>(), channels) {
        value = withContext(Dispatchers.Default) { channels.distinctBy { it.key }.map { it to foldText(it.name) } }
    }
    val channelHits = remember(q, index) {
        if (q.length < 2) emptyList()
        else foldText(q).let { needle -> index.filter { it.second.contains(needle) }.map { it.first }.take(40) }
    }

    val hubs = plex?.getOrNull().orEmpty()
    val plexDone = q.length < 2 || !Graph.plex.configured || plexQuery == q
    val focus = rememberRestorableFocus(autoFocus, plexDone)
    focus.defaultKey = "field"
    val firstResult = remember { FocusRequester() }
    var focused by remember { mutableStateOf<Any?>(null) }
    ReportAmbient(
        when (val f = focused) {
            is PlexItem -> plexAmbient(f)
            is Channel -> liveAmbient(f)
            else -> null
        }
    )
    // After the Search key: move to the first result as soon as there is one.
    LaunchedEffect(focusResults, hubs, channelHits, plexDone) {
        if (focusResults && plexDone) {
            focusResults = false
            if (hubs.isNotEmpty() || channelHits.isNotEmpty()) {
                delay(80)
                runCatching { firstResult.requestFocus() }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 26.dp)) {
        Text("Search", color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = EdgePadding))
        SearchField(
            query, { query = it },
            onSearch = { focusResults = true; submitted++ },
            modifier = focus.modifier("field").padding(start = EdgePadding, top = 12.dp),
        )

        val status = when {
            q.length < 2 -> "Type a title or a channel."
            !plexDone && hubs.isEmpty() && channelHits.isEmpty() -> "Searching"
            plex?.isFailure == true && plexQuery == q && channelHits.isEmpty() ->
                "Plex search failed: ${plex?.exceptionOrNull()?.message}"
            plexDone && hubs.isEmpty() && channelHits.isEmpty() -> "Nothing found for \"$q\"."
            else -> null
        }
        if (status != null) {
            Text(status, color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = EdgePadding, top = 22.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(top = 18.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (q.length >= 2) hubs.forEachIndexed { h, hub ->
                item(key = "hub:" + hub.id) {
                    PosterRow(hub.title) {
                        itemsIndexed(hub.items, key = { _, it -> hub.id + ":" + it.ratingKey }) { i, item ->
                            PosterCard(
                                item, Graph.plex.image(item.poster, 240, 360),
                                onClick = { onOpenItem(item) },
                                onFocus = { focused = item },
                                cardModifier = focus.modifier("${hub.id}:${item.ratingKey}")
                                    .then(if (h == 0 && i == 0) Modifier.focusRequester(firstResult) else Modifier),
                                width = RowPosterWidth,
                            )
                        }
                    }
                }
            }
            if (channelHits.isNotEmpty()) item(key = "channels") {
                PosterRow("Live TV") {
                    itemsIndexed(channelHits, key = { i, c -> "ch:$i|${c.key}" }) { i, ch ->
                        ChannelCard(
                            channel = ch,
                            now = ch.epgId?.let { epg[it] }?.nowAndNext()?.first,
                            favorite = ch.key in favorites,
                            onClick = { onPlayChannel("Search", channelHits, i) },
                            onLongClick = { vm.toggleFavorite(ch) },
                            onFocus = { focused = ch },
                            cardModifier = focus.modifier("ch:${ch.key}")
                                .then(if (hubs.isEmpty() && i == 0) Modifier.focusRequester(firstResult) else Modifier),
                        )
                    }
                }
            }
        }
    }
}

/** The text field: OK opens the keyboard, Down goes to the results, Left on an empty field to the menu. */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = TextMain, fontSize = 18.sp, fontFamily = MasalFont),
        cursorBrush = SolidColor(Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSearch() }),
        modifier = modifier
            .width(520.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (e.type == KeyEventType.KeyUp) keyboard?.show()
                        true
                    }
                    Key.DirectionDown -> {
                        if (e.type == KeyEventType.KeyDown) { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) }
                        true
                    }
                    Key.DirectionLeft -> {
                        if (value.isEmpty()) {
                            if (e.type == KeyEventType.KeyDown) focusManager.moveFocus(FocusDirection.Left)
                            true
                        } else false
                    }
                    else -> false
                }
            },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .border(if (hasFocus) 2.dp else 1.dp, if (hasFocus) Accent else Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = if (hasFocus) Accent else TextDim, modifier = Modifier.size(22.dp))
                Box(Modifier.padding(start = 12.dp).fillMaxWidth()) {
                    if (value.isEmpty()) {
                        Text(if (hasFocus) "Press OK to type or speak" else "Movies, shows, channels", color = TextDim, fontSize = 16.sp)
                    }
                    inner()
                }
            }
        },
    )
}
