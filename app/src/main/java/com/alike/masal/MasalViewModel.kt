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
import com.alike.masal.data.PlexHub
import com.alike.masal.data.PlexItem
import com.alike.masal.data.PlexSection
import com.alike.masal.data.Programme
import com.alike.masal.data.TvApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class MasalViewModel(app: Application) : AndroidViewModel(app) {
    val channels = MutableStateFlow<List<Channel>>(emptyList())
    val epg = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val onDeck = MutableStateFlow<List<PlexItem>>(emptyList())
    val recentlyAdded = MutableStateFlow<List<PlexItem>>(emptyList())
    /** Plex's own Home rows (recently added, promoted collections); Continue Watching comes from [onDeck]. */
    val homeHubs = MutableStateFlow<List<PlexHub>>(emptyList())
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
            hubs.onSuccess { list -> homeHubs.value = list.filter { it.id != "home.continue" && it.id != "home.ondeck" } }
                .onFailure { // older servers: fall back to one "Recently added" row
                    val recent = runCatching { Graph.plex.recentlyAdded() }
                    ensureActive()
                    recent.onSuccess { recentlyAdded.value = it }
                }
        }
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
