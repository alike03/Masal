package com.alike.masal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.MasalViewModel
import com.alike.masal.R
import com.alike.masal.data.PlexHub
import com.alike.masal.data.PlexItem
import com.alike.masal.data.PlexSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.text.Normalizer
import java.util.Locale
import androidx.annotation.StringRes

enum class LibTab(@StringRes val title: Int) {
    Recommended(R.string.browse_tab_recommended), Browse(R.string.browse_tab_browse),
    Collections(R.string.browse_tab_collections), Categories(R.string.browse_tab_categories),
}

/** A Plex library like in Plex: Recommended rows, Browse (sortable, A–Z), Collections, Categories. */
@Composable
fun LibraryScreen(
    vm: MasalViewModel,
    section: PlexSection,
    autoFocus: Boolean,
    onItem: (PlexItem) -> Unit,
    onPlay: (PlexItem) -> Unit,
    onCollection: (PlexItem) -> Unit,
    onGenre: (id: String, name: String) -> Unit,
) {
    var tab by rememberSaveable(section.key) { mutableStateOf(LibTab.Recommended) }
    var pending by remember { mutableStateOf(tab) }
    // Only the first content takes focus by itself; later tab switches keep focus on the tab bar.
    var switched by remember { mutableStateOf(false) }
    LaunchedEffect(pending) {
        if (pending != tab) { delay(150); switched = true; tab = pending }
    }
    val rows = rememberRowsState()
    val contentFocus = autoFocus && !switched

    Box(Modifier.fillMaxSize()) {
        when (tab) {
            LibTab.Recommended -> RecommendedTab(vm, section, contentFocus, rows, onItem, onPlay)
            LibTab.Browse -> BrowseTab(vm, section, contentFocus, onItem)
            LibTab.Collections -> CollectionsTab(vm, section, contentFocus, onCollection)
            LibTab.Categories -> CategoriesTab(vm, section, contentFocus, onGenre)
        }
        Row(
            Modifier.padding(start = EdgePadding, top = 16.dp)
                .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                .padding(4.dp)
                .focusGroup()
                .onPreviewKeyEvent { e ->
                    // Rows above the focused one are folded away; go straight to the visible one.
                    if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown && tab == LibTab.Recommended && pending == tab) {
                        rows.focusCurrent()
                    } else false
                },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LibTab.entries.forEach { t ->
                TabPill(stringResource(t.title), t == pending, onFocus = { pending = t })
            }
        }
    }
}

@Composable
fun TabPill(title: String, selected: Boolean, onFocus: () -> Unit, onClick: () -> Unit = onFocus, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) TextMain else TextDim,
            focusedContainerColor = TextMain,
            focusedContentColor = OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Text(
            title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .then(if (selected) Modifier.border(1.5.dp, TextMain.copy(alpha = 0.85f), RoundedCornerShape(50)) else Modifier)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}

// ---- Recommended ----

@Composable
private fun RecommendedTab(
    vm: MasalViewModel,
    section: PlexSection,
    autoFocus: Boolean,
    rowsState: RowsState,
    onItem: (PlexItem) -> Unit,
    onPlay: (PlexItem) -> Unit,
) {
    val state by produceState<Result<List<PlexHub>>?>(null, section.key) {
        value = withContext(Dispatchers.IO) { runCatching { vm.sectionHubs(section.key) } }
    }
    val hubs = state?.getOrNull().orEmpty()
    var hero by remember { mutableStateOf<Pair<PlexItem, Boolean>?>(null) }
    val focus = rememberRestorableFocus(autoFocus, hubs.isNotEmpty())
    focus.defaultKey = hubs.firstOrNull()?.let { h -> h.items.firstOrNull()?.let { "${h.id}:${it.ratingKey}" } }
    val shown = hero ?: hubs.firstOrNull()?.items?.firstOrNull()?.let { it to hubs.first().id.contains("inprogress") }
    ReportAmbient(shown?.let { plexAmbient(it.first) })

    val rows = hubs.map { hub ->
        val resume = hub.id.contains("inprogress")
        RowSpec(hub.id, PosterRowHeight) {
            PosterRow(hub.title) {
                items(hub.items, key = { hub.id + ":" + it.ratingKey }) { item ->
                    PosterCard(
                        item, Graph.plex.image(item.poster, 240, 360),
                        onClick = { if (resume) onPlay(item) else onItem(item) },
                        onFocus = { hero = item to resume },
                        cardModifier = focus.modifier("${hub.id}:${item.ratingKey}"),
                        width = RowPosterWidth, showLabels = false,
                    )
                }
            }
        }
    }
    var heroShown by remember { mutableStateOf(shown?.first) }
    LaunchedEffect(shown?.first) { delay(HeroSettle); heroShown = shown?.first }
    // Rows slide up under the tab bar: clip them just below it.
    FoldingRows(rows, RowsTop + 6.dp - LibTabsHeight, rowsState, Modifier.padding(top = LibTabsHeight).clipToBounds()) {
        HeroSwap(heroShown, { it?.ratingKey }, Modifier.fillMaxSize().padding(start = EdgePadding, top = 18.dp)) { h ->
            if (h != null) HeroPlex(h, logoHeight = 60.dp)
            else Text(
                when {
                    state == null -> stringResource(R.string.browse_loading)
                    state?.isFailure == true -> stringResource(R.string.browse_load_failed, state?.exceptionOrNull()?.message.orEmpty())
                    else -> stringResource(R.string.browse_nothing_recommended)
                },
                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 40.dp),
            )
        }
    }
}

/** Space the library tab bar takes at the top of the page. */
private val LibTabsHeight = 58.dp

// ---- Browse ----

enum class SortBy(@StringRes val label: Int, val defaultDesc: Boolean) {
    Title(R.string.browse_sort_title, false), Year(R.string.browse_sort_year, true), Released(R.string.browse_sort_released, true),
    Added(R.string.browse_sort_added, true), Rating(R.string.browse_sort_rating, true)
}

private val collator: Collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }

/** Index letter of a title: A–Z without accents, '#' for everything else. */
fun indexLetter(title: String): Char {
    val c = Normalizer.normalize(title.trim().take(1).uppercase().replace("İ", "I"), Normalizer.Form.NFD).firstOrNull() ?: '#'
    return if (c in 'A'..'Z') c else '#'
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BrowseTab(vm: MasalViewModel, section: PlexSection, autoFocus: Boolean, onItem: (PlexItem) -> Unit) {
    val state by produceState<Result<List<PlexItem>>?>(null, section.key) {
        value = withContext(Dispatchers.IO) { runCatching { vm.sectionItems(section.key) } }
    }
    val items = state?.getOrNull().orEmpty()
    var sort by rememberSaveable { mutableStateOf(SortBy.Title) }
    var desc by rememberSaveable { mutableStateOf(false) }
    val sorted = remember(items, sort, desc) {
        val cmp: Comparator<PlexItem> = when (sort) {
            SortBy.Title -> Comparator { a, b -> collator.compare(a.titleSort, b.titleSort) }
            SortBy.Year -> compareBy<PlexItem> { it.year ?: 0 }.thenBy { it.titleSort.lowercase() }
            SortBy.Released -> compareBy<PlexItem> { it.released ?: "" }
            SortBy.Added -> compareBy<PlexItem> { it.addedAt }
            SortBy.Rating -> compareBy<PlexItem> { it.rating ?: 0.0 }
        }
        items.sortedWith(if (desc) cmp.reversed() else cmp)
    }
    // First position of each letter, for the A–Z bar (only when sorted by title).
    val letters = remember(sorted, sort) {
        if (sort != SortBy.Title) emptyList()
        else sorted.withIndex().map { indexLetter(it.value.titleSort) to it.index }.distinctBy { it.first }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val focus = rememberRestorableFocus(autoFocus, sorted.isNotEmpty())
    focus.defaultKey = sorted.firstOrNull()?.ratingKey
    var current by remember { mutableStateOf<PlexItem?>(null) }
    var jumpIndex by remember { mutableIntStateOf(-1) }
    val jump = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    // Scroll once the remote rests on a letter. Scrolling inside the focus change itself
    // would create new poster cells mid-change, and Compose then drops the focus.
    LaunchedEffect(jumpIndex) {
        if (jumpIndex < 0) return@LaunchedEffect
        delay(140)
        grid.scrollToItem(jumpIndex)
    }
    ReportAmbient(Ambient("browse:" + section.key, null))

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(start = EdgePadding, top = 66.dp).focusGroup()
                .onPreviewKeyEvent { e ->
                    // Down goes to the posters, not to the A–Z bar on the right.
                    if (e.key == Key.DirectionDown && e.type == KeyEventType.KeyDown) runCatching { gridFocus.requestFocus() }.isSuccess
                    else false
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.browse_sort), color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
            SortBy.entries.forEach { s ->
                val active = s == sort
                val label = stringResource(s.label)
                TabPill(
                    if (active) label + if (desc) "  ↓" else "  ↑" else label, active, onFocus = {},
                    onClick = {
                        if (active) desc = !desc else { sort = s; desc = s.defaultDesc }
                        scope.launch { grid.scrollToItem(0) }
                    },
                )
            }
            Text(
                when {
                    state == null -> stringResource(R.string.browse_loading)
                    state?.isFailure == true -> stringResource(R.string.browse_load_failed, state?.exceptionOrNull()?.message.orEmpty())
                    else -> pluralStringResource(
                        if (section.type == "movie") R.plurals.browse_movies_count else R.plurals.browse_shows_count, items.size, items.size,
                    )
                },
                color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp),
            )
        }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(7),
            contentPadding = PaddingValues(start = EdgePadding, end = 44.dp, top = 10.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            // Below the tabs and sort options, so posters never scroll underneath them.
            modifier = Modifier.fillMaxSize().padding(top = 102.dp).focusRequester(gridFocus),
        ) {
            itemsIndexed(sorted, key = { _, it -> it.ratingKey }) { i, item ->
                PosterCard(
                    item, Graph.plex.image(item.poster, 240, 360),
                    onClick = { onItem(item) },
                    onFocus = { current = item },
                    cardModifier = focus.modifier(item.ratingKey).then(if (i == jumpIndex) Modifier.focusRequester(jump) else Modifier),
                    width = 102.dp,
                )
            }
        }
        if (letters.size > 1) {
            val active = current?.let { indexLetter(it.titleSort) }
            Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp, top = 40.dp).focusGroup()
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.DirectionLeft) && jumpIndex >= 0) {
                            runCatching { jump.requestFocus() }.isSuccess
                        } else false
                    },
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                letters.forEach { (letter, index) ->
                    LetterKey(letter, letter == active,
                        onFocus = { jumpIndex = index },
                        onClick = { jumpIndex = index; scope.launch { grid.scrollToItem(index); delay(60); runCatching { jump.requestFocus() } } })
                }
            }
        }
    }
}

@Composable
private fun LetterKey(letter: Char, active: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(width = 26.dp, height = 15.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = if (active) Accent else TextDim,
            focusedContainerColor = TextMain, focusedContentColor = OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.25f),
    ) {
        Text("$letter", fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
    }
}

// ---- Collections and categories ----

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CollectionsTab(vm: MasalViewModel, section: PlexSection, autoFocus: Boolean, onCollection: (PlexItem) -> Unit) {
    val state by produceState<Result<List<PlexItem>>?>(null, section.key) {
        value = withContext(Dispatchers.IO) { runCatching { vm.collections(section.key) } }
    }
    val unit = if (section.type == "movie") R.plurals.browse_movies_count else R.plurals.browse_shows_count
    val items = state?.getOrNull().orEmpty().map { it.copy(subtitle = pluralStringResource(unit, it.childCount, it.childCount)) }
    ItemsGrid(items, state, autoFocus, "collections:" + section.key, top = 66.dp, empty = stringResource(R.string.browse_no_collections), onItem = onCollection)
}

@Composable
private fun CategoriesTab(vm: MasalViewModel, section: PlexSection, autoFocus: Boolean, onGenre: (String, String) -> Unit) {
    val state by produceState<Result<List<Pair<String, String>>>?>(null, section.key) {
        value = withContext(Dispatchers.IO) { runCatching { vm.genres(section.key) } }
    }
    val items by produceState(emptyList<PlexItem>(), section.key) {
        value = withContext(Dispatchers.IO) { runCatching { vm.sectionItems(section.key) }.getOrDefault(emptyList()) }
    }
    val genres = state?.getOrNull().orEmpty()
    // A picture per category: artwork of a title in it, each picture used once if possible.
    val art = remember(genres, items) {
        val used = HashSet<String>()
        genres.associate { (_, name) ->
            val pool = items.filter { name in it.genres && it.art != null }
            val pick = pool.firstOrNull { it.art !in used } ?: pool.firstOrNull()
            pick?.art?.let { used += it }
            name to pick?.art
        }
    }
    val focus = rememberRestorableFocus(autoFocus, genres.isNotEmpty())
    focus.defaultKey = genres.firstOrNull()?.first
    ReportAmbient(Ambient("categories:" + section.key, null))
    Box(Modifier.fillMaxSize()) {
        if (genres.isEmpty()) {
            Text(stringResource(if (state == null) R.string.browse_loading else R.string.browse_no_categories), color = TextDim, fontSize = 14.sp,
                modifier = Modifier.padding(start = EdgePadding, top = 76.dp))
        }
        LazyVerticalGrid(
            state = rememberLazyGridState(),
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = EdgePadding, end = EdgePadding, top = 10.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            // Grids start below the tab bar: focus search skips a grid that overlaps the tab you press Down on.
            modifier = Modifier.fillMaxSize().padding(top = 62.dp),
        ) {
            itemsIndexed(genres, key = { _, g -> g.first }) { _, (id, name) ->
                LibraryCard(name, "", Graph.plex.image(art[name], 640, 360), { onGenre(id, name) },
                    cardModifier = focus.modifier(id), width = 196.dp)
            }
        }
    }
}

/** Poster grid with titles, used for collections, a collection's titles and a category. */
@Composable
fun ItemsGrid(
    items: List<PlexItem>,
    state: Result<*>?,
    autoFocus: Boolean,
    ambientKey: String,
    top: androidx.compose.ui.unit.Dp,
    empty: String,
    onItem: (PlexItem) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    val focus = rememberRestorableFocus(autoFocus, items.isNotEmpty())
    focus.defaultKey = items.firstOrNull()?.ratingKey
    // Pages (a collection, a category) get the first title's artwork behind the header.
    val backdrop = if (header != null) items.firstOrNull { it.art != null } else null
    ReportAmbient(Ambient(ambientKey, backdrop?.let { Graph.plex.image(it.art, 1920, 1080) }, colors = backdrop?.blur.orEmpty()))
    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Text(
                when {
                    state == null -> stringResource(R.string.browse_loading)
                    state.isFailure -> stringResource(R.string.browse_load_failed, state.exceptionOrNull()?.message.orEmpty())
                    else -> empty
                },
                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(start = EdgePadding, top = top + 10.dp),
            )
        }
        LazyVerticalGrid(
            state = rememberLazyGridState(),
            columns = GridCells.Fixed(7),
            contentPadding = PaddingValues(start = EdgePadding, end = EdgePadding, top = 10.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().padding(top = (top - 4.dp).coerceAtLeast(0.dp)),
        ) {
            if (header != null) item(key = "header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
            itemsIndexed(items, key = { _, it -> it.ratingKey }) { _, item ->
                PosterCard(
                    item, Graph.plex.image(item.poster, 240, 360),
                    onClick = { onItem(item) },
                    cardModifier = focus.modifier(item.ratingKey),
                    width = 104.dp,
                )
            }
        }
    }
}

/** A collection or a category opened from a library: header and all its titles. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ListPage(title: String, subtitle: String?, autoFocus: Boolean, load: suspend () -> List<PlexItem>, onItem: (PlexItem) -> Unit) {
    val state by produceState<Result<List<PlexItem>>?>(null, title) {
        value = withContext(Dispatchers.IO) { runCatching { load() } }
    }
    val items = state?.getOrNull().orEmpty()
    ItemsGrid(items, state, autoFocus, "list:$title", top = 0.dp, empty = stringResource(R.string.browse_empty), onItem = onItem, header = {
        Column(Modifier.padding(top = 30.dp, bottom = 8.dp)) {
            Text(title, color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            val count = if (items.isNotEmpty()) pluralStringResource(R.plurals.browse_titles_count, items.size, items.size) else null
            listOfNotNull(subtitle, count).joinToString("   ").takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp).width(600.dp))
            }
        }
    })
}

// ---- Detail ----

/** Detail page for a film or a series (with seasons and episodes). */
@Composable
fun DetailScreen(
    ratingKey: String,
    initialSeason: String?,
    autoFocus: Boolean,
    profile: String? = null,
    onPerson: (com.alike.masal.data.CastMember) -> Unit = {},
    onPlay: (PlexItem, Boolean) -> Unit,
) {
    // Opened from a profile's row: seasons, next episode and watched marks are that member's.
    val plex = remember(profile) { Graph.plexFor(profile) }
    val meta by produceState<Result<PlexItem>?>(null, ratingKey) {
        value = withContext(Dispatchers.IO) { runCatching { plex.metadata(ratingKey) } }
    }
    val item = meta?.getOrNull()
    val isShow = item?.type == "show"

    val seasons by produceState(emptyList<PlexItem>(), ratingKey, isShow) {
        if (isShow) value = withContext(Dispatchers.IO) { runCatching { plex.children(ratingKey) }.getOrDefault(emptyList()) }
    }
    val nextUp by produceState<PlexItem?>(null, ratingKey, isShow) {
        if (isShow) value = withContext(Dispatchers.IO) { runCatching { plex.showOnDeck(ratingKey) }.getOrNull() }
    }
    var seasonKey by rememberSaveable { mutableStateOf(initialSeason) }
    val activeSeason = seasons.firstOrNull { it.ratingKey == seasonKey }
        ?: seasons.firstOrNull { it.leafCount > it.viewedLeafCount && it.index > 0 }
        ?: seasons.firstOrNull()
    val episodes by produceState(emptyList<PlexItem>(), activeSeason?.ratingKey) {
        val key = activeSeason?.ratingKey ?: return@produceState
        value = emptyList()
        value = withContext(Dispatchers.IO) { runCatching { plex.children(key) }.getOrDefault(emptyList()) }
    }
    var focusedEpisode by remember { mutableStateOf<PlexItem?>(null) }
    // Series without progress have no "next up" from Plex: offer the first episode of the shown season.
    val playTarget = if (isShow) nextUp ?: episodes.firstOrNull { it.playable } else item
    // Focus only once the Play button exists, otherwise nothing on the page would have focus.
    val focus = rememberRestorableFocus(autoFocus, item != null && playTarget != null)
    focus.defaultKey = "play"

    // Cast & Crew row (people.md): Seerr's cast where it knows the title, else Plex's.
    val cast by produceState(emptyList<com.alike.masal.data.CastMember>(), item) {
        val it = item ?: return@produceState
        value = it.people
        value = castFor(it)
    }

    val trailer by produceState<PlexItem?>(null, ratingKey) {
        value = withContext(Dispatchers.IO) { Graph.plex.trailer(ratingKey) }
    }
    ReportAmbient(item?.let { plexAmbient(it) })

    Box(Modifier.fillMaxSize()) {
        val m = meta
        when {
            m == null -> Text(stringResource(R.string.browse_loading), color = TextDim, modifier = Modifier.align(Alignment.Center))
            m.isFailure || item == null -> Text(stringResource(R.string.browse_load_failed, m.exceptionOrNull()?.message.orEmpty()), color = Accent,
                modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 40.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "header") {
                    Column(Modifier.padding(horizontal = EdgePadding).width(540.dp)) {
                        TitleArt(item, height = 100.dp)
                        item.tagline?.let {
                            Text(it, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                modifier = Modifier.padding(top = 10.dp))
                        }
                        MetaLine(item, Modifier.padding(top = 8.dp))
                        val ep = focusedEpisode
                        val text = if (ep != null) stringResource(R.string.browse_episode_short, ep.index) + "  ${ep.episodeTitle.orEmpty()}\n${ep.summary.orEmpty()}" else item.summary
                        text?.let {
                            Text(it, color = TextMain.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 4,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                        }
                        if (item.cast.isNotEmpty()) {
                            Text(item.cast.joinToString(", "), color = TextDim, fontSize = 12.sp, maxLines = 1,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val target = playTarget
                            if (target != null && target.playable) {
                                val label = when {
                                    target.viewOffset > 0 -> stringResource(R.string.browse_resume, formatClock(target.viewOffset))
                                    isShow -> stringResource(R.string.browse_play_episode, target.parentIndex, target.index)
                                    else -> stringResource(R.string.browse_play)
                                }
                                MasalButton(label, { onPlay(target, false) }, focus.modifier("play"), Icons.Filled.PlayArrow)
                                if (target.viewOffset > 0) {
                                    MasalButton(stringResource(R.string.browse_from_start), { onPlay(target, true) }, icon = Icons.Filled.Refresh, primary = false)
                                }
                            }
                            trailer?.let { t ->
                                MasalButton(stringResource(R.string.browse_trailer), { onPlay(t, true) }, icon = Icons.Filled.Info, primary = false)
                            }
                        }
                    }
                }
                if (isShow && seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = EdgePadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(seasons, key = { "s:" + it.ratingKey }) { s ->
                                SeasonTab(s.subtitle.ifBlank { stringResource(R.string.browse_season, s.index) }, s.ratingKey == activeSeason?.ratingKey,
                                    { seasonKey = s.ratingKey }, focus.modifier("s:${s.ratingKey}"))
                            }
                        }
                    }
                    item(key = "episodes") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(episodes, key = { "e:" + it.ratingKey }) { ep ->
                                EpisodeCard(
                                    ep, Graph.plex.image(ep.still ?: ep.art, 400, 225),
                                    onClick = { onPlay(ep, false) },
                                    onFocus = { focusedEpisode = ep },
                                    cardModifier = focus.modifier("e:${ep.ratingKey}"),
                                )
                            }
                        }
                    }
                }
                if (cast.isNotEmpty()) item(key = "cast") {
                    CastRow(cast, onPerson) { i -> focus.modifier("cast:$i") }
                }
            }
        }
    }
}

@Composable
private fun SeasonTab(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onSelect,
        modifier = modifier.onFocusChanged { if (it.isFocused) onSelect() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PanelFocus else Color(0x66000000),
            contentColor = if (selected) Accent else TextDim,
            focusedContainerColor = TextMain,
            focusedContentColor = Bg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Accent.copy(alpha = 0.5f), 10.dp)),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}
