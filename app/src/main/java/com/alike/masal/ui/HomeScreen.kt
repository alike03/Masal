package com.alike.masal.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.MasalViewModel
import com.alike.masal.R
import com.alike.masal.data.Backdrop
import com.alike.masal.data.Channel
import com.alike.masal.data.PlexItem
import com.alike.masal.data.HomeRows
import com.alike.masal.data.SeerrStatus
import com.alike.masal.data.SeerrTitle
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
    /** A title in one of Seerr's Discover rows (seerr.md). */
    data class Seerr(val title: SeerrTitle) : Hero
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
    /** Raised by Back in the side menu (Masal as home screen): go to the first card of the first row. */
    topRequests: Int = 0,
    /** Where a title in a Seerr row leads (seerr.md); null leaves Seerr rows out. */
    seerr: PeopleActions? = null,
) {
    val channels by vm.channels.collectAsState()
    val epg by vm.epg.collectAsState()
    val onDeck by vm.onDeck.collectAsState()
    val recentlyAdded by vm.recentlyAdded.collectAsState()
    val hubs by vm.homeHubs.collectAsState()
    val profileDecks by vm.profileDecks.collectAsState()
    val allApps by vm.apps.collectAsState()
    val homeKeys by vm.homeApps.collectAsState()
    val backdrops by vm.backdrops.collectAsState()
    val iptvErrors by vm.iptvErrors.collectAsState()
    val plexError by vm.plexError.collectAsState()
    val seerrRows by vm.seerrRows.collectAsState()
    val requested by Graph.seerr.requested.collectAsState()
    val homeOrder by Graph.settings.homeOrder.collectAsState()

    val apps = remember(allApps, homeKeys) { val byPkg = allApps.associateBy { it.pkg }; homeKeys.mapNotNull { byPkg[it] } }
    val settingsVersion by Graph.settings.version.collectAsState() // the row's category is set in Settings
    val homeVersion by Graph.settings.homeVersion.collectAsState()
    val favourites by vm.favorites.collectAsState()
    val tvRow = remember(channels, settingsVersion, homeVersion, favourites) {
        val saved = Graph.settings.homeLiveGroup
        val group = MasalViewModel.normGroup(saved)
        if (group.isEmpty()) emptyList()
        else if (saved == com.alike.masal.data.Settings.FavouritesGroup) channels.filter { it.key in favourites }.distinctBy { it.key }
        else {
            // Normalise each distinct category name once, not every channel (lists can hold 30 000 entries).
            val wanted = channels.mapTo(HashSet()) { it.group }.filterTo(HashSet()) { MasalViewModel.normGroup(it) == group }
            channels.filter { it.group in wanted }.distinctBy { it.key }
        }
    }
    val nothingSetUp = Graph.settings.sources.isEmpty() && !Graph.plex.configured

    var hero by remember { mutableStateOf<Hero?>(null) }
    val recentTitle = stringResource(R.string.home_recently_added)
    val plexRows = hubs.ifEmpty {
        if (recentlyAdded.isNotEmpty()) listOf(com.alike.masal.data.PlexHub("recent", recentTitle, recentlyAdded)) else emptyList()
    }
    // Each sortable row's first card, in the default order: Home starts on the first row of the user's order.
    val firstCards = buildList {
        if (allApps.isNotEmpty()) add(HomeRows.AppsRow to (apps.firstOrNull()?.let { "app:${it.pkg}" } ?: "app:add"))
        if (onDeck.isNotEmpty()) add(HomeRows.DeckRow to "deck:0")
        profileDecks.forEach { add("hub:${it.id}" to "${it.id}:0") }
        tvRow.firstOrNull()?.let { add(HomeRows.LiveRow to "tv:${it.key}") }
        plexRows.forEach { h -> h.items.firstOrNull()?.let { add("hub:${h.id}" to "h:${h.id}:${it.ratingKey}") } }
        if (seerr != null) seerrRows.forEach { r -> r.titles.firstOrNull()?.let { add(r.id to "${r.id}:${it.mediaType}:${it.tmdbId}") } }
    }.toMap()
    val firstKey = HomeRows.ordered(firstCards.keys.toList(), homeOrder).firstOrNull()?.let { firstCards[it] }
    val focus = rememberRestorableFocus(autoFocus, firstKey != null)
    focus.defaultKey = firstKey
    var moving by remember { mutableStateOf<String?>(null) }

    // Until something has focus, show the first title of Continue Watching.
    val shown = hero ?: onDeck.firstOrNull()?.let { Hero.Plex(it, resume = true) }
    val rowsState = rememberRowsState()
    ReportAmbient(
        when (shown) {
            is Hero.Live -> liveAmbient(shown.channel)
            is Hero.Plex -> plexAmbient(shown.item)
            // Apps get a landscape photo, never movie artwork; without photos the background stays plain.
            is Hero.App -> backdropFor(shown.app.pkg, backdrops)?.let { Ambient("app:" + it.url, it.url) }
            Hero.EditApps -> backdrops.firstOrNull()?.let { Ambient("app:" + it.url, it.url) }
            is Hero.Seerr -> seerrAmbient(shown.title)
            null -> null
        },
        group = rowsState.focusedKey,
    )

    val defaultRows = buildList {
        if (nothingSetUp) add(RowSpec("setup", BannerRowHeight) {
            Column {
                RowTitle(stringResource(R.string.home_get_started))
                LazyRow(contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 10.dp)) {
                    item { LibraryCard(stringResource(R.string.home_setup_title), stringResource(R.string.home_setup_text), null, onOpenSettings) }
                }
            }
        })
        if (allApps.isNotEmpty()) add(RowSpec("apps", BannerRowHeight) {
            Column {
                RowTitle(stringResource(if (moving != null) R.string.home_apps_moving else R.string.common_apps))
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
                        AddAppsCard(stringResource(if (apps.isEmpty()) R.string.home_choose_apps else R.string.common_edit), onEditApps,
                            { hero = Hero.EditApps }, focus.modifier("app:add"))
                    }
                }
            }
        })
        if (onDeck.isNotEmpty()) add(RowSpec("deck", PosterRowHeight) {
            PosterRow(stringResource(R.string.home_continue_watching)) {
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
        profileDecks.forEach { deck ->
            add(RowSpec("hub:${deck.id}", PosterRowHeight) {
                PosterRow(deck.title, owner = deck.owner) {
                    // Keyed by position like the main Continue Watching.
                    itemsIndexed(deck.items, key = { i, _ -> "${deck.id}:$i" }) { i, item ->
                        PosterCard(
                            item, Graph.plex.image(item.poster, 240, 360),
                            onClick = { onPlayItem(item) },
                            onFocus = { hero = Hero.Plex(item, resume = true) },
                            cardModifier = focus.modifier("${deck.id}:$i"),
                            width = RowPosterWidth, showLabels = false,
                        )
                    }
                }
            })
        }
        if (tvRow.isNotEmpty()) add(RowSpec("tv", ChannelRowHeight) {
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
        plexRows.forEach { hub ->
            add(RowSpec("hub:${hub.id}", PosterRowHeight) {
                PosterRow(hub.title, collectionKind(hub), hub.owner) {
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
        if (seerr != null) seerrRows.forEach { row ->
            add(RowSpec(row.id, PosterRowHeight) {
                // "Seerr" next to the title, like a profile's name on its rows (seerr.md).
                PosterRow(row.title, owner = HomeRows.SeerrGroup) {
                    items(row.titles, key = { "${row.id}:${it.mediaType}:${it.tmdbId}" }) { t0 ->
                        // A request from Masal marks the title at once, before the row is loaded again.
                        val t = if (t0.status == SeerrStatus.None && "${t0.mediaType}:${t0.tmdbId}" in requested) t0.copy(status = SeerrStatus.Requested) else t0
                        SeerrPosterCard(
                            t, null,
                            onClick = { openSeerrTitle(t, seerr) },
                            modifier = focus.modifier("${row.id}:${t.mediaType}:${t.tmdbId}"),
                            width = RowPosterWidth, showLabels = false,
                            onFocus = { hero = Hero.Seerr(t) },
                        )
                    }
                }
            })
        }
    }
    // The user's order (home.md, "Row order"); "Get started" always stays first.
    val rows = remember(defaultRows.map { it.key }, homeOrder) {
        val byKey = defaultRows.associateBy { it.key }
        val sortable = defaultRows.filter { it.key != "setup" }.map { it.key }
        listOfNotNull(byKey["setup"]?.key) + HomeRows.ordered(sortable, homeOrder)
    }.mapNotNull { key -> defaultRows.firstOrNull { it.key == key } }

    // The hero text follows the focus once it rests for a moment, so holding a key does not flicker.
    // Moving to another row it follows at once: that row's info slot opens right away and must not
    // show the previous row's text, not even for a frame.
    var heroShown by remember { mutableStateOf(shown) }
    var heroRow by remember { mutableStateOf<String?>(null) }
    var rowJump by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        val row = rowsState.focusedKey
        val jump = row != heroRow
        if (!jump) delay(HeroSettle)
        rowJump = jump; heroRow = row; heroShown = shown
    }

    // Handled once: when Home is built again (back from a page or the player) the request is old.
    var handledTop by rememberSaveable { mutableIntStateOf(topRequests) }
    LaunchedEffect(topRequests) {
        if (topRequests == handledTop) return@LaunchedEffect
        handledTop = topRequests
        rowsState.focusedKey = rowsState.order.firstOrNull() ?: return@LaunchedEffect
        // The first row is composed once it is the focused one; then its first card can take focus.
        repeat(2) { androidx.compose.runtime.withFrameNanos { } }
        if (!focus.focusDefault()) rowsState.focusCurrent()
    }

    FoldingRows(rows, RowsTop, rowsState) {
        HeroSwap(
            // All apps share one key: the clock above them stays put while moving along the row.
            heroShown, { h -> when (h) { is Hero.Plex -> h.item.ratingKey; is Hero.Live -> h.channel.key; is Hero.App, Hero.EditApps -> "apps"
                is Hero.Seerr -> "seerr:${h.title.mediaType}:${h.title.tmdbId}"; null -> null } },
            Modifier.fillMaxSize().padding(start = EdgePadding, top = 56.dp),
            cut = rowJump,
        ) { h ->
            when (h) {
                is Hero.Plex -> HeroPlex(h.item)
                is Hero.Live -> HeroChannel(h.channel, h.channel.epgId?.let { epg[it] })
                // Apps: only the photo behind and the time, no name and no photo credit.
                is Hero.App, Hero.EditApps -> HomeClock()
                is Hero.Seerr -> HeroSeerr(h.title)
                null -> {
                    val status = (iptvErrors + listOfNotNull(plexError)).firstOrNull()
                    if (status != null) Text(status, color = Accent, fontSize = 13.sp, maxLines = 2)
                }
            }
        }
    }
}

/**
 * OK on a title in a Seerr row (seerr.md): a film in Plex plays, a series in Plex opens its
 * detail page, anything else opens the Seerr title page.
 */
private fun openSeerrTitle(t: SeerrTitle, actions: PeopleActions) {
    val rk = t.ratingKey
    when {
        t.status == SeerrStatus.Available && rk != null && t.mediaType == "movie" && actions.onPlayPlex != null -> actions.onPlayPlex.invoke(rk, t.title)
        t.status == SeerrStatus.Available && rk != null -> actions.onPlexTitle(rk, t.title)
        else -> actions.onSeerrTitle(t)
    }
}

/** Info block for a Seerr title: title, year and kind, two lines of summary. */
@Composable
fun HeroSeerr(t: SeerrTitle, modifier: Modifier = Modifier) {
    Column(modifier.width(430.dp)) {
        Text(t.title, color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            lineHeight = 34.sp)
        val kind = stringResource(if (t.mediaType == "tv") R.string.home_seerr_kind_tv else R.string.home_seerr_kind_movie)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(t.year?.toString(), kind).joinToString("   "), color = TextDim, fontSize = 13.sp, maxLines = 1)
            StatusBadge(t.status, Modifier.padding(start = 10.dp), t.date)
        }
        t.overview?.let {
            Text(it, color = TextMain.copy(alpha = 0.92f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun PosterRow(title: String, kind: String? = null, owner: String? = null, content: LazyListScope.() -> Unit) {
    Column {
        if (kind == null && owner == null) RowTitle(title)
        else Row(verticalAlignment = Alignment.Bottom) {
            RowTitle(title)
            // A profile's row: the member's name first, then Movies / TV shows (profiles.md), with a thin line between.
            listOfNotNull(owner, kind).forEachIndexed { i, label ->
                if (i > 0) Box(Modifier.padding(start = 10.dp, bottom = 7.dp).size(width = 1.dp, height = 12.dp).background(TextDim.copy(alpha = 0.6f)))
                Text(label, color = TextDim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** For a collection row: whether it holds movies or TV shows (a collection's title alone does not tell). */
@Composable
private fun collectionKind(hub: com.alike.masal.data.PlexHub): String? {
    if (!hub.id.startsWith("collection:") && ":collection:" !in hub.id) return null
    val types = hub.items.mapTo(HashSet()) { if (it.type == "movie") "movie" else if (it.type in setOf("show", "season", "episode")) "show" else it.type }
    return when (types.singleOrNull()) {
        "movie" -> stringResource(R.string.home_movie_collection)
        "show" -> stringResource(R.string.home_show_collection)
        else -> null
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

/** The time above the apps row (Home only); updates on the minute. */
@Composable
private fun HomeClock() {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000 - System.currentTimeMillis() % 60_000); value = System.currentTimeMillis() }
    }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    Text(fmt.format(java.util.Date(now)), color = TextMain, fontSize = 52.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp))
}
