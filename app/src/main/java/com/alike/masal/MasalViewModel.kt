package com.alike.masal

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alike.masal.data.Apps
import com.alike.masal.data.Backdrop
import com.alike.masal.data.Backdrops
import com.alike.masal.data.Channel
import com.alike.masal.data.HomeRowOption
import com.alike.masal.data.HomeRows
import com.alike.masal.data.PlexHub
import com.alike.masal.data.PlexItem
import com.alike.masal.data.PlexSection
import com.alike.masal.data.Programme
import com.alike.masal.data.TvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.alike.masal.data.SeerrSlider
import com.alike.masal.data.SeerrTitle

/** A Seerr Discover slider shown on Home (seerr.md). */
data class SeerrRow(val id: String, val title: String, val titles: List<SeerrTitle>)

/** A slider's name: its own title, or Masal's name for Seerr's built-in sliders. */
fun seerrRowTitle(s: SeerrSlider): String = s.title ?: com.alike.masal.data.Language.text(
    when (s.type) {
        SeerrSlider.Trending -> R.string.home_seerr_trending
        SeerrSlider.PopularMovies -> R.string.home_seerr_popular_movies
        SeerrSlider.UpcomingMovies -> R.string.home_seerr_upcoming_movies
        SeerrSlider.PopularTv -> R.string.home_seerr_popular_tv
        SeerrSlider.UpcomingTv -> R.string.home_seerr_upcoming_tv
        else -> R.string.home_seerr_row
    },
)

class MasalViewModel(app: Application) : AndroidViewModel(app) {
    val channels = MutableStateFlow<List<Channel>>(emptyList())
    val epg = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val onDeck = MutableStateFlow<List<PlexItem>>(emptyList())
    val recentlyAdded = MutableStateFlow<List<PlexItem>>(emptyList())
    /** The Plex rows shown on Home, as chosen in Settings; Continue Watching comes from [onDeck]. */
    val homeHubs = MutableStateFlow<List<PlexHub>>(emptyList())
    /** Continue Watching of each profile whose row is switched on, in profile order (profiles.md). */
    val profileDecks = MutableStateFlow<List<PlexHub>>(emptyList())
    /** What was loaded as each profile: Continue Watching, Plex's Home rows and collections per library. */
    private class ProfileRows(val deck: List<PlexItem>, val hubs: List<PlexHub>, val collections: List<Pair<PlexSection, PlexItem>>)
    @Volatile private var profileRows: Map<String, ProfileRows> = emptyMap()
    /** Everything that can be shown as a row on Home (Plex's rows and all collections), for Settings. */
    val homeRowOptions = MutableStateFlow<List<HomeRowOption>>(emptyList())
    /** Seerr's Discover sliders that can be rows on Home (seerr.md); empty without Seerr. */
    val seerrSliders = MutableStateFlow<List<SeerrSlider>>(emptyList())
    /** The Seerr rows switched on, in Seerr's order, each with its first page of titles. */
    val seerrRows = MutableStateFlow<List<SeerrRow>>(emptyList())
    private var seerrJob: Job? = null
    /** Plex's own Home rows as loaded, before the user's choice. */
    @Volatile private var plexHomeHubs: List<PlexHub> = emptyList()
    /** First titles of collections shown on Home that Plex does not promote. */
    private val extraRowCache = java.util.concurrent.ConcurrentHashMap<String, List<PlexItem>>()
    private var homeRowsJob: Job? = null
    val recentChannels = MutableStateFlow(Graph.settings.recentChannels)
    internal val sectionCache = java.util.concurrent.ConcurrentHashMap<String, List<PlexItem>>() // internal: tests seed it
    internal val hubCache = java.util.concurrent.ConcurrentHashMap<String, List<PlexHub>>() // internal: tests seed it
    private val collectionCache = java.util.concurrent.ConcurrentHashMap<String, List<PlexItem>>()
    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, List<Pair<String, String>>>()
    val sections = MutableStateFlow<List<PlexSection>>(emptyList())
    val apps = MutableStateFlow<List<TvApp>>(emptyList())
    /** Apps pinned to Home, in order (package names). */
    val homeApps = MutableStateFlow(Graph.settings.homeApps)
    val backdrops = MutableStateFlow<List<Backdrop>>(emptyList())
    val favorites = MutableStateFlow(Graph.settings.favorites)
    /** Last Plex search (query and result), so Back from a title shows the results at once. */
    internal var lastSearch: Pair<String, Result<List<PlexHub>>>? = null
    /** The last Seerr search (seerr.md), kept like [lastSearch]. */
    internal var lastSeerrSearch: Pair<String, com.alike.masal.data.SeerrSearch>? = null
    val iptvErrors = MutableStateFlow<List<String>>(emptyList())
    val plexError = MutableStateFlow<String?>(null)
    val loadingChannels = MutableStateFlow(false)

    private var iptvJob: Job? = null
    private var plexJob: Job? = null
    private var lastIptvLoad = 0L

    /**
     * As the home screen Masal starts with the device, often before the network is up. When a
     * network becomes available, everything that failed or never loaded is loaded again.
     */
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { viewModelScope.launch { retryFailedLoads() } }
    }
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    init {
        viewModelScope.launch(Dispatchers.IO) { apps.value = Apps.load(getApplication()) }
        viewModelScope.launch {
            Graph.settings.version.collect {
                sectionCache.clear(); hubCache.clear(); collectionCache.clear(); genreCache.clear()
                reloadIptv()
                refreshPlex()
            }
        }
        // Only the Home layout changed (rows chosen in Settings): rebuild the rows, reload nothing else.
        viewModelScope.launch { Graph.settings.homeVersion.drop(1).collect { rebuildHomeRows(); refreshSeerrRows(reloadSliders = false) } }
        loadBackdrops()
        runCatching { connectivity?.registerDefaultNetworkCallback(network) }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(network) }
    }

    /** Only loads that are not running: the callback also fires right after registering. */
    internal fun retryFailedLoads() {
        val sources = Graph.settings.sources
        if (iptvJob?.isActive != true && sources.isNotEmpty() && (iptvErrors.value.isNotEmpty() || channels.value.isEmpty())) {
            reloadIptv()
        }
        if (plexJob?.isActive != true && Graph.plex.configured && (plexError.value != null || sections.value.isEmpty())) {
            refreshPlex()
        }
        if (backdropJob?.isActive != true && backdrops.value.isEmpty()) loadBackdrops()
    }

    private var backdropJob: Job? = null

    fun loadBackdrops() {
        backdropJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { Backdrops.load(Graph.http) }.onSuccess { backdrops.value = it }
                .onFailure { android.util.Log.w("MasalBg", "backdrops: ${it.message}") }
        }
    }

    fun reloadIptv() {
        iptvJob?.cancel()
        lastIptvLoad = System.currentTimeMillis()
        iptvJob = viewModelScope.launch(Dispatchers.IO) {
            loadingChannels.value = true
            val ch = Graph.iptv.loadChannels()
            // The loads block without suspending, so cancel() cannot stop them: a superseded load
            // (sources changed meanwhile) must not write its old result.
            ensureActive()
            channels.value = ch.data
            iptvErrors.value = ch.errors
            loadingChannels.value = false
            val guide = Graph.iptv.loadEpg(ch.data)
            ensureActive()
            epg.value = guide.data
            iptvErrors.value = ch.errors + guide.errors
        }
    }

    fun refreshPlex() {
        if (!Graph.plex.configured) {
            onDeck.value = emptyList(); recentlyAdded.value = emptyList(); sections.value = emptyList(); homeHubs.value = emptyList()
            homeRowOptions.value = emptyList(); plexHomeHubs = emptyList(); profileRows = emptyMap(); profileDecks.value = emptyList()
            return
        }
        plexJob?.cancel()
        plexJob = viewModelScope.launch(Dispatchers.IO) {
            // As with IPTV: a superseded refresh (e.g. an old, wrong server URL) must not write its result.
            val first = runCatching { Graph.plex.onDeck() to Graph.plex.sections() }
            ensureActive()
            first.onSuccess { (deck, secs) -> onDeck.value = deck; sections.value = secs; plexError.value = null }
                .onFailure { plexError.value = "Plex: ${it.message ?: it.javaClass.simpleName}" }
            val hubs = runCatching { Graph.plex.homeHubs() }
            ensureActive()
            plexHomeHubs = hubs.getOrNull()?.filter { it.id != "home.continue" && it.id != "home.ondeck" }.orEmpty()
            extraRowCache.clear()
            hubs.onFailure { // older servers: fall back to one "Recently added" row
                val recent = runCatching { Graph.plex.recentlyAdded() }
                ensureActive()
                recent.onSuccess { recentlyAdded.value = it }
            }
            refreshSeerrRows()
            if (Graph.settings.plexAccountName.isBlank()) runCatching { Graph.plex.accountName() }.getOrNull()?.let { Graph.settings.plexAccountName = it }
            profileRows = loadProfiles()
            ensureActive()
            buildHomeRows()
        }
    }

    /** Each profile's rows, loaded as that member; a profile whose token the server refuses is marked and skipped. */
    private suspend fun loadProfiles(): Map<String, ProfileRows> {
        val out = LinkedHashMap<String, ProfileRows>()
        for (p in Graph.settings.profiles) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val client = Graph.plexFor(p.id)
            val deck = runCatching { client.onDeck() }
            if (deck.exceptionOrNull() is com.alike.masal.data.ProfileRejectedException) {
                Graph.settings.profilesRejected = Graph.settings.profilesRejected + p.id
                continue
            }
            if (deck.isSuccess) Graph.settings.profilesRejected = Graph.settings.profilesRejected - p.id
            val hubs = runCatching { client.homeHubs() }.getOrDefault(emptyList())
                .filter { it.id != "home.continue" && it.id != "home.ondeck" }
            val collections = sections.value.flatMap { s ->
                runCatching { client.collections(s.key) }.getOrDefault(emptyList()).map { s to it }
            }
            out[p.id] = ProfileRows(deck.getOrDefault(emptyList()), hubs, collections)
        }
        return out
    }

    /**
     * Seerr's sliders (asked again with [reloadSliders]) and the rows switched on: each from the
     * cache when younger than 12 hours, else from Seerr. Unreachable Seerr: no rows, no error.
     */
    fun refreshSeerrRows(reloadSliders: Boolean = true) {
        seerrJob?.cancel()
        if (!Graph.seerr.configured) { seerrSliders.value = emptyList(); seerrRows.value = emptyList(); return }
        seerrJob = viewModelScope.launch(Dispatchers.IO) {
            val sliders = if (reloadSliders || seerrSliders.value.isEmpty()) {
                runCatching { Graph.seerr.sliders() }.getOrNull() ?: seerrSliders.value
            } else seerrSliders.value
            ensureActive()
            seerrSliders.value = sliders
            val on = Graph.settings.homeRowsOn
            val today = java.time.LocalDate.now().toString()
            val rows = sliders.filter { it.rowId in on }.mapNotNull { sl ->
                val titles = Graph.seerr.cachedDiscover(sl) ?: runCatching { Graph.seerr.discover(sl, today) }.getOrNull()
                titles?.takeIf { it.isNotEmpty() }?.let { SeerrRow(sl.rowId, seerrRowTitle(sl), it) }
            }
            ensureActive()
            seerrRows.value = rows
        }
    }

    fun rebuildHomeRows() {
        if (!Graph.plex.configured) return
        homeRowsJob?.cancel()
        homeRowsJob = viewModelScope.launch(Dispatchers.IO) { buildHomeRows() }
    }

    /**
     * Home's Plex rows: Plex's own rows in Plex's order, then extra collections in library order,
     * each only when switched on in Settings (see Settings.homeRowShown).
     */
    private suspend fun buildHomeRows() {
        val collections = sections.value.flatMap { s ->
            runCatching { collections(s.key) }.getOrDefault(emptyList()).map { s to it }
        }
        val hubs = plexHomeHubs
        val options = HomeRows.options(hubs, collections)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val shown = options.filter { Graph.settings.homeRowShown(it) }
        val shownIds = shown.mapTo(HashSet()) { it.id }
        val rows = ArrayList<PlexHub>()
        for (h in hubs) {
            val id = HomeRows.collectionKey(h.id)?.let { "collection:$it" } ?: h.id
            if (id in shownIds && rows.none { it.id == id }) rows += h.copy(id = id)
        }
        for (o in shown) {
            if (!o.id.startsWith("collection:") || rows.any { it.id == o.id }) continue
            val key = o.id.removePrefix("collection:")
            val items = extraRowCache[key]
                ?: runCatching { Graph.plex.collectionItems(key).take(20) }.getOrNull()?.also { extraRowCache[key] = it }
            if (!items.isNullOrEmpty()) rows += PlexHub(o.id, o.title, items)
        }
        // Profiles: their Continue Watching goes after the main one, their other rows after the main rows.
        val decks = ArrayList<PlexHub>()
        val allOptions = ArrayList(options)
        val continueTitle = com.alike.masal.data.Language.text(R.string.home_continue_watching)
        for (p in Graph.settings.profiles) {
            val loaded = profileRows[p.id] ?: continue
            val pOptions = HomeRows.profileOptions(p, continueTitle, loaded.hubs, loaded.collections)
            allOptions += pOptions
            val on = pOptions.filter { Graph.settings.homeRowShown(it) }
            val onIds = on.mapTo(HashSet()) { it.rowId }
            if (HomeRows.Continue in onIds && loaded.deck.isNotEmpty()) {
                decks += PlexHub(HomeRows.profilePrefix(p.id) + HomeRows.Continue, continueTitle, loaded.deck, owner = p.name)
            }
            val pRows = ArrayList<PlexHub>()
            for (h in loaded.hubs) {
                val id = HomeRows.collectionKey(h.id)?.let { "collection:$it" } ?: h.id
                if (id in onIds && pRows.none { it.id.endsWith(":$id") }) pRows += h.copy(id = HomeRows.profilePrefix(p.id) + id, owner = p.name)
            }
            for (o in on) {
                val rowId = o.rowId
                if (!rowId.startsWith("collection:") || pRows.any { it.id == o.id }) continue
                val key = rowId.removePrefix("collection:")
                val items = extraRowCache[o.id]
                    ?: runCatching { Graph.plexFor(p.id).collectionItems(key).take(20) }.getOrNull()?.also { extraRowCache[o.id] = it }
                if (!items.isNullOrEmpty()) pRows += PlexHub(o.id, o.title, items, owner = p.name)
            }
            rows += pRows
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        homeRowOptions.value = allOptions
        profileDecks.value = decks
        homeHubs.value = rows
    }

    /** Library contents are cached for the session; tabs switch often. */
    suspend fun sectionItems(key: String): List<PlexItem> =
        sectionCache[key] ?: Graph.plex.sectionItems(key).also { sectionCache[key] = it }

    suspend fun sectionHubs(key: String): List<PlexHub> =
        hubCache[key] ?: Graph.plex.sectionHubs(key).let { hubs ->
            // The rows asked for first, in this order, then the rest of Plex's suggestions.
            val order = listOf("inprogress", "recentlyadded", "recentlyreleased", "recentlyaired", "recentlyviewed")
            hubs.sortedBy { h -> order.indexOfFirst { h.id.contains(".$it") }.let { if (it < 0) order.size else it } }
        }.also { hubCache[key] = it }

    suspend fun collections(sectionKey: String): List<PlexItem> =
        collectionCache[sectionKey] ?: Graph.plex.collections(sectionKey).also { collectionCache[sectionKey] = it }

    suspend fun genres(sectionKey: String): List<Pair<String, String>> =
        genreCache[sectionKey] ?: Graph.plex.genres(sectionKey).also { genreCache[sectionKey] = it }

    fun onResumeRefresh() {
        recentChannels.value = Graph.settings.recentChannels
        hubCache.clear()
        refreshPlex()
        refreshApps()
        // The guide covers the next 24 h from loading; after hours in the background reload it (and the channels).
        if (System.currentTimeMillis() - lastIptvLoad > 3 * 60 * 60_000L) reloadIptv()
    }

    fun refreshApps() {
        viewModelScope.launch(Dispatchers.IO) { apps.value = Apps.load(getApplication()) }
    }

    fun toggleFavorite(ch: Channel) {
        val set = favorites.value.toMutableSet()
        if (!set.add(ch.key)) set.remove(ch.key)
        Graph.settings.favorites = set
        favorites.value = set
    }

    /** Channel categories in the user's order (see [CategoryOrder]). */
    val categoryOrder = MutableStateFlow(Graph.settings.categoryOrder)

    /** Category names of the loaded channels, in the user's order. */
    // The TV tab's and the live player's lists, built off the main thread: a provider can list tens of
    // thousands of channels, and grouping them on every visit and channel start costs frames.
    private val channelsByKey = channels.map { all -> all.associateBy { it.key } }.flowOn(Dispatchers.Default)

    /** Channel categories in the user's order (each with its channels in the provider's order). */
    val channelCategories: StateFlow<List<Pair<String, List<Channel>>>> = combine(channels, categoryOrder) { all, order ->
        com.alike.masal.data.CategoryOrder.groups(all.groupBy { it.group }.toList(), order)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Last watched channels, newest first. */
    val recentList: StateFlow<List<Channel>> = combine(channelsByKey, recentChannels) { byKey, keys -> keys.mapNotNull { byKey[it] } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Favourite channels in the provider's order. */
    val favouriteList: StateFlow<List<Channel>> = combine(channels, favorites) { all, favs ->
        if (favs.isEmpty()) emptyList() else all.filter { it.key in favs }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun categoryNames(): List<String> =
        com.alike.masal.data.CategoryOrder.apply(channels.value.map { it.group }.filter { it.isNotBlank() }.distinct(), categoryOrder.value)

    fun moveCategory(name: String, delta: Int) = setCategoryOrder(com.alike.masal.data.CategoryOrder.move(categoryNames(), name, delta))

    fun setCategoryOrder(order: List<String>) {
        Graph.settings.categoryOrder = order
        categoryOrder.value = order
    }

    fun toggleHomeApp(pkg: String) {
        val list = homeApps.value.toMutableList()
        if (!list.remove(pkg)) list.add(pkg)
        Graph.settings.homeApps = list
        homeApps.value = list
    }

    /** Moves a Home app one place left (-1) or right (+1). */
    fun moveHomeApp(pkg: String, delta: Int) {
        val list = homeApps.value.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.removeAt(i); list.add(j, pkg)
        Graph.settings.homeApps = list
        homeApps.value = list
    }

    companion object {
        /** Upper case without accents and provider prefix: "[XX] Café News" -> "CAFE NEWS". */
        fun normGroup(g: String): String =
            com.alike.masal.data.foldText(g).uppercase(java.util.Locale.ROOT).replace(GroupPrefix, "").trim()

        private val GroupPrefix = Regex("^\\[[A-Z]{2,3}]\\s*")
    }
}
