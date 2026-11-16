package com.alike.masal.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.MasalViewModel
import com.alike.masal.data.Backdrop
import com.alike.masal.data.Channel
import com.alike.masal.data.PlexItem
import com.alike.masal.data.TvApp
import com.alike.masal.data.nowAndNext
import kotlinx.coroutines.delay
import kotlin.math.abs

sealed interface Hero {
    data class Plex(val item: PlexItem, val resume: Boolean = false) : Hero
    data class Live(val channel: Channel) : Hero
    data class App(val app: TvApp) : Hero
    /** The "Edit" card at the end of the apps row. */
    data object EditApps : Hero
}

/** Height of the open header (hero text) above the focused row on rows pages. */
val RowsTop = 268.dp

/** How long focus rests on a title before the hero text follows it. */
const val HeroSettle = 90L

/** Background photo for an app: the same one each time, picked from the current set. */
@Suppress("UNUSED_PARAMETER") // one calm photo for all apps; the parameter keeps call sites readable
fun backdropFor(pkg: String, backdrops: List<Backdrop>): Backdrop? = backdrops.firstOrNull()

/** Keys that put a Home app into "move" mode (no long press over HDMI-CEC). */
val MoveKeys = setOf(Key.MediaPlayPause, Key.MediaPlay, Key.Menu)

@Composable
fun HomeScreen(
    vm: MasalViewModel,
    autoFocus: Boolean,
    onOpenSettings: () -> Unit,
    onOpenItem: (PlexItem) -> Unit,
    onPlayItem: (PlexItem) -> Unit,
    onPlayChannel: (String, List<Channel>, Int) -> Unit,
    onApp: (TvApp) -> Unit,
    onEditApps: () -> Unit,
) {
    val channels by vm.channels.collectAsState()
    val epg by vm.epg.collectAsState()
    val onDeck by vm.onDeck.collectAsState()
    val recentlyAdded by vm.recentlyAdded.collectAsState()
    val hubs by vm.homeHubs.collectAsState()
    val allApps by vm.apps.collectAsState()
    val homeKeys by vm.homeApps.collectAsState()
    val backdrops by vm.backdrops.collectAsState()
    val iptvErrors by vm.iptvErrors.collectAsState()
    val plexError by vm.plexError.collectAsState()

    val apps = remember(allApps, homeKeys) { val byPkg = allApps.associateBy { it.pkg }; homeKeys.mapNotNull { byPkg[it] } }
    val settingsVersion by Graph.settings.version.collectAsState() // the row's category is set in Settings
    val homeVersion by Graph.settings.homeVersion.collectAsState()
    val tvRow = remember(channels, settingsVersion, homeVersion) {
        val group = MasalViewModel.normGroup(Graph.settings.homeLiveGroup)
        if (group.isEmpty()) emptyList()
        else {
            // Normalise each distinct category name once, not every channel (lists can hold 30 000 entries).
            val wanted = channels.mapTo(HashSet()) { it.group }.filterTo(HashSet()) { MasalViewModel.normGroup(it) == group }
            channels.filter { it.group in wanted }.distinctBy { it.key }
        }
    }
    val nothingSetUp = Graph.settings.sources.isEmpty() && !Graph.plex.configured

    var hero by remember { mutableStateOf<Hero?>(null) }
    val firstKey = apps.firstOrNull()?.let { "app:${it.pkg}" } ?: if (allApps.isNotEmpty()) "app:add" else null
    val focus = rememberRestorableFocus(autoFocus, firstKey != null)
    focus.defaultKey = firstKey
    var moving by remember { mutableStateOf<String?>(null) }

    // Until something has focus, show the first title of Continue Watching.
    val shown = hero ?: onDeck.firstOrNull()?.let { Hero.Plex(it, resume = true) }
    ReportAmbient(
        when (shown) {
            is Hero.Live -> liveAmbient(shown.channel)
            is Hero.Plex -> plexAmbient(shown.item)
            // Apps get a landscape photo, never movie artwork; without photos the background stays plain.
            is Hero.App -> backdropFor(shown.app.pkg, backdrops)?.let { Ambient("app:" + it.url, it.url) }
            Hero.EditApps -> backdrops.firstOrNull()?.let { Ambient("app:" + it.url, it.url) }
            null -> null
        }
    )

    val rows = buildList {
        if (nothingSetUp) add(RowSpec("setup", BannerRowHeight) {
            Column {
                RowTitle("Get started")
                LazyRow(contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 10.dp)) {
                    item { LibraryCard("Set up Masal", "Add IPTV and Plex with your phone", null, onOpenSettings) }
                }
            }
        })
        if (allApps.isNotEmpty()) add(RowSpec("apps", BannerRowHeight) {
            Column {
                RowTitle(if (moving != null) "Apps   ◀ ▶ move, OK done" else "Apps")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        val m = moving ?: return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft, Key.DirectionRight -> {
                                if (e.type == KeyEventType.KeyDown) vm.moveHomeApp(m, if (e.key == Key.DirectionLeft) -1 else 1)
                                true
                            }
                            Key.DirectionCenter, Key.Enter, Key.Back -> { if (e.type == KeyEventType.KeyUp) moving = null; true }
                            in MoveKeys -> { if (e.type == KeyEventType.KeyUp) moving = null; true }
                            else -> true // stay on the row while moving
                        }
                    },
                ) {
                    items(apps, key = { "app:" + it.pkg }) { app ->
                        AppCard(
                            app, { onApp(app) }, { hero = Hero.App(app) },
                            focus.modifier("app:${app.pkg}").onPreviewKeyEvent { e ->
                                if (e.key in MoveKeys && e.type == KeyEventType.KeyUp && moving == null) { moving = app.pkg; true }
                                else e.key in MoveKeys && moving == null
                            },
                            moving = moving == app.pkg,
                        )
                    }
                    item(key = "app:add") {
                        AddAppsCard(if (apps.isEmpty()) "Choose apps for Home" else "Edit", onEditApps,
                            { hero = Hero.EditApps }, focus.modifier("app:add"))
                    }
                }
            }
        })
        if (onDeck.isNotEmpty()) add(RowSpec("deck", PosterRowHeight) {
            PosterRow("Continue Watching") {
                // Keyed by position: when a finished episode is replaced by the next one after a refresh,
                // the focused slot stays and keeps focus instead of being removed.
                itemsIndexed(onDeck, key = { i, _ -> "deck:$i" }) { i, item ->
                    PosterCard(
                        item, Graph.plex.image(item.poster, 240, 360),
                        onClick = { onPlayItem(item) },
                        onFocus = { hero = Hero.Plex(item, resume = true) },
                        cardModifier = focus.modifier("deck:$i"),
                        width = RowPosterWidth, showLabels = false,
                    )
                }
            }
        })
        if (tvRow.isNotEmpty()) add(RowSpec("tv", BannerRowHeight) {
            Column {
                RowTitle(Graph.settings.homeLiveTitle)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(tvRow, key = { i, c -> "tv:$i|${c.key}" }) { i, ch ->
                        ChannelCard(
                            channel = ch,
                            now = ch.epgId?.let { epg[it] }?.nowAndNext()?.first,
                            favorite = false,
                            onClick = { onPlayChannel(Graph.settings.homeLiveTitle, tvRow, i) },
                            onLongClick = { vm.toggleFavorite(ch) },
                            onFocus = { hero = Hero.Live(ch) },
                            cardModifier = focus.modifier("tv:${ch.key}"),
                            groupFallback = false,
                        )
                    }
                }
            }
        })
        val plexRows = hubs.ifEmpty {
            if (recentlyAdded.isNotEmpty()) listOf(com.alike.masal.data.PlexHub("recent", "Recently Added", recentlyAdded)) else emptyList()
        }
        plexRows.forEach { hub ->
            add(RowSpec("hub:${hub.id}", PosterRowHeight) {
                PosterRow(hub.title) {
                    items(hub.items, key = { "h:${hub.id}:" + it.ratingKey }) { item ->
                        PosterCard(
                            item, Graph.plex.image(item.poster, 240, 360),
                            onClick = { onOpenItem(item) },
                            onFocus = { hero = Hero.Plex(item) },
                            cardModifier = focus.modifier("h:${hub.id}:${item.ratingKey}"),
                            width = RowPosterWidth, showLabels = false,
                        )
                    }
                }
            })
        }
    }

    // The hero text follows the focus once it rests for a moment, so holding a key does not flicker.
    var heroShown by remember { mutableStateOf(shown) }
    LaunchedEffect(shown) { delay(HeroSettle); heroShown = shown }

    FoldingRows(rows, RowsTop) {
        HeroSwap(
            heroShown, { h -> when (h) { is Hero.Plex -> h.item.ratingKey; is Hero.Live -> h.channel.key; is Hero.App -> h.app.pkg; Hero.EditApps -> "edit"; null -> null } },
            Modifier.fillMaxSize().padding(start = EdgePadding, top = 56.dp),
        ) { h ->
            when (h) {
                is Hero.Plex -> HeroPlex(h.item)
                is Hero.Live -> HeroChannel(h.channel, h.channel.epgId?.let { epg[it] })
                // Apps: only the photo behind, no name and no photo credit.
                is Hero.App, Hero.EditApps -> Unit
                null -> {
                    val status = (iptvErrors + listOfNotNull(plexError)).firstOrNull()
                    if (status != null) Text(status, color = Accent, fontSize = 13.sp, maxLines = 2)
                }
            }
        }
    }
}

@Composable
fun PosterRow(title: String, content: LazyListScope.() -> Unit) {
    Column {
        RowTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Last card of the Home apps row: opens the Apps page to choose and order apps. */
@Composable
fun AddAppsCard(label: String, onClick: () -> Unit, onFocus: () -> Unit, cardModifier: Modifier) {
    Column {
        androidx.tv.material3.Surface(
            onClick = onClick,
            modifier = cardModifier.size(168.dp, 94.5.dp).onFocusChanged { if (it.isFocused) onFocus() },
            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(cardShape),
            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.08f), focusedContainerColor = TextMain,
                contentColor = TextMain, focusedContentColor = OnAccent,
            ),
            scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        ) {
            Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.2f), cardShape), contentAlignment = Alignment.Center) {
                Text("+  $label", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
