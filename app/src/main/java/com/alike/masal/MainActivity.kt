package com.alike.masal

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import com.alike.masal.player.logKey
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.alike.masal.data.Apps
import com.alike.masal.data.Channel
import com.alike.masal.data.PlexItem
import com.alike.masal.data.PlexSection
import com.alike.masal.player.PlayerActivity
import com.alike.masal.setup.SetupScreen
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.rememberCoroutineScope
import com.alike.masal.ui.LocalEntryFocus
import com.alike.masal.ui.MasalIcons
import com.alike.masal.ui.RailEntry
import com.alike.masal.ui.RailWidth
import com.alike.masal.ui.SearchScreen
import com.alike.masal.ui.SideRail
import com.alike.masal.ui.Accent
import com.alike.masal.ui.Ambient
import com.alike.masal.ui.AmbientBackground
import com.alike.masal.ui.LocalAmbient
import com.alike.masal.ui.Bg
import com.alike.masal.ui.DetailScreen
import com.alike.masal.ui.EdgePadding
import com.alike.masal.ui.HomeScreen
import com.alike.masal.ui.LibraryScreen
import com.alike.masal.ui.ListPage
import com.alike.masal.ui.AppsScreen
import com.alike.masal.ui.MasalTheme
import com.alike.masal.ui.NavHeight
import com.alike.masal.ui.TextMain
import com.alike.masal.ui.TvScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface NavTab {
    val key: String
    /** Menu label as a string resource; 0 for libraries, which show the Plex section's own title. */
    @get:StringRes val titleRes: Int

    data object Search : NavTab { override val key = "search"; override val titleRes = R.string.common_search }
    data object Home : NavTab { override val key = "home"; override val titleRes = R.string.common_home }
    data object Tv : NavTab { override val key = "tv"; override val titleRes = R.string.common_tv }
    data class Library(val section: PlexSection) : NavTab {
        override val key get() = "lib:" + section.key
        override val titleRes get() = 0
        val title get() = section.title
    }
    data object Apps : NavTab { override val key = "apps"; override val titleRes = R.string.common_apps }
    data object Settings : NavTab { override val key = "settings"; override val titleRes = R.string.common_settings }
}

/** Pages opened on top of a tab: a title's details, a collection or a category. */
data class Detail(
    val ratingKey: String,
    val season: String? = null,
    val kind: String = "item",
    val title: String = "",
    val section: String = "",
) {
    // Section and season are part of the key: genre ids repeat across libraries, and a show opened
    // from another season must not restore the old page state.
    val key get() = "page:$kind:$section:$ratingKey:${season.orEmpty()}"
}

class MainActivity : ComponentActivity() {
    private val vm: MasalViewModel by viewModels()
    private var resumedOnce = false
    /** Masal is the device's default home screen: Back on Home then stays here instead of leaving. */
    internal var isHome by mutableStateOf(false) // internal: the UI tests set it
    /** Counts Home key presses while Masal runs; each one returns to the start of Home. */
    private var homePresses by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MasalTheme {
                val sections by vm.sections.collectAsState()
                val tabs = remember(sections) {
                    listOf(NavTab.Search, NavTab.Home, NavTab.Tv) + sections.map { NavTab.Library(it) } + NavTab.Apps + NavTab.Settings
                }
                val holder = rememberSaveableStateHolder()
                var tabKey by rememberSaveable { mutableStateOf(NavTab.Home.key) }
                val tab = tabs.firstOrNull { it.key == tabKey } ?: NavTab.Home
                val stack = remember { mutableStateListOf<Detail>() }
                // Content takes focus on start and after Back, not while moving along the tab bar.
                var autoFocus by remember { mutableStateOf(true) }

                var editApps by remember { mutableStateOf(false) }
                fun selectTab(t: NavTab, focusContent: Boolean) {
                    autoFocus = focusContent
                    if (t != NavTab.Apps) editApps = false
                    tabKey = t.key
                }
                fun open(d: Detail) { autoFocus = true; stack.add(d) }

                val railRequester = remember { FocusRequester() }
                var railFocused by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                // Back: close a page, then go to the menu, then to Home, then leave the app
                // (unless Masal is the home screen: there is nothing to leave to).
                BackHandler(enabled = stack.isNotEmpty() || !railFocused || tab != NavTab.Home || isHome) {
                    when {
                        stack.isNotEmpty() -> {
                            autoFocus = true
                            val closed = stack.removeAt(stack.lastIndex).key
                            // Forget the closed page's saved state once it has faded out, so opening it again starts fresh.
                            scope.launch { delay(600); if (stack.none { it.key == closed }) holder.removeState(closed) }
                        }
                        !railFocused -> runCatching { railRequester.requestFocus() }
                        tab != NavTab.Home -> {
                            selectTab(NavTab.Home, false)
                            scope.launch { delay(40); runCatching { railRequester.requestFocus() } }
                        }
                        else -> Unit // home screen, already on Home
                    }
                }


                val openItem: (PlexItem) -> Unit = { item ->
                    when (item.type) {
                        "movie", "show" -> open(Detail(item.ratingKey, null))
                        "season" -> open(Detail(item.parentRatingKey ?: item.ratingKey, item.ratingKey))
                        "collection" -> open(Detail(item.ratingKey, kind = "collection", title = item.title))
                        else -> playPlex(item, fromStart = false)
                    }
                }

                val top = stack.lastOrNull()
                val screenKey = top?.key ?: tab.key
                // Each screen reports its background; only the visible screen's one is drawn.
                val ambients = remember { mutableStateMapOf<String, Ambient?>() }
                // Where focus goes when leaving the menu, registered by each screen.
                val entries = remember { mutableMapOf<String, () -> Boolean>() }
                val focusManager = LocalFocusManager.current
                val enterContent = {
                    val ok = entries[screenKey]?.invoke() == true
                    if (!ok) focusManager.moveFocus(FocusDirection.Right)
                }
                // Moving through the menu switches the page only once it rests on an entry, so
                // quickly passing over Movies does not load the whole library on the way.
                var railTarget by remember { mutableStateOf<NavTab?>(null) }
                val currentTab by rememberUpdatedState(tab) // the effect outlives the composition it started in
                LaunchedEffect(railTarget) {
                    val t = railTarget ?: return@LaunchedEffect
                    delay(150)
                    if (t != currentTab) selectTab(t, false)
                }

                // Home key: close all pages and go back to Home, as the stock home screen does.
                LaunchedEffect(homePresses) {
                    if (homePresses == 0) return@LaunchedEffect
                    val closed = stack.map { it.key }
                    stack.clear()
                    railTarget = null
                    selectTab(NavTab.Home, true)
                    delay(600)
                    closed.forEach { holder.removeState(it) }
                }

                Box(Modifier.fillMaxSize().background(Bg)) {
                    AmbientBackground(ambients[screenKey])
                    AnimatedContent(
                        modifier = Modifier.fillMaxSize().padding(start = RailWidth),
                        targetState = screenKey,
                        transitionSpec = {
                            val deeper = targetState.startsWith("page:") && !initialState.startsWith("page:")
                            if (deeper) (fadeIn(tween(320)) + scaleIn(tween(320), initialScale = 0.97f)) togetherWith fadeOut(tween(160))
                            else fadeIn(tween(200)) togetherWith fadeOut(tween(120))
                        },
                        label = "screen",
                    ) { key ->
                        CompositionLocalProvider(
                            LocalAmbient provides { a -> ambients[key] = a },
                            LocalEntryFocus provides { r -> entries[key] = r },
                        ) {
                            holder.SaveableStateProvider(key) {
                                // Remembered so a closing page keeps its content while it fades out.
                                val detail = remember(key) { stack.lastOrNull { it.key == key } }
                                val t = tabs.firstOrNull { it.key == key }
                                when {
                                    detail != null && detail.kind == "collection" -> ListPage(detail.title, stringResource(R.string.common_collection), autoFocus,
                                        { Graph.plex.collectionItems(detail.ratingKey) }, openItem)
                                    detail != null && detail.kind == "genre" -> ListPage(detail.title, stringResource(R.string.common_category), autoFocus,
                                        { Graph.plex.genreItems(detail.section, detail.ratingKey) }, openItem)
                                    detail != null -> DetailScreen(detail.ratingKey, detail.season, autoFocus) { item, fromStart ->
                                        playPlex(item, fromStart)
                                    }
                                    t == NavTab.Home -> HomeScreen(
                                        vm = vm,
                                        autoFocus = autoFocus,
                                        onOpenSettings = { selectTab(NavTab.Settings, true) },
                                        onOpenItem = openItem,
                                        onPlayItem = { playPlex(it, fromStart = false) },
                                        onPlayChannel = { name, list, i -> playChannels(name, list, i, fromHome = true) },
                                        onApp = { Apps.launch(this@MainActivity, it.pkg) },
                                        onEditApps = { editApps = true; selectTab(NavTab.Apps, true) },
                                    )
                                    t == NavTab.Search -> SearchScreen(vm, autoFocus, openItem, ::playChannels)
                                    t == NavTab.Apps -> AppsScreen(vm, autoFocus, editApps) { Apps.launch(this@MainActivity, it.pkg) }
                                    t == NavTab.Tv -> TvScreen(vm, autoFocus, ::playChannels)
                                    t is NavTab.Library -> LibraryScreen(
                                        vm, t.section, autoFocus, openItem,
                                        onPlay = { playPlex(it, fromStart = false) },
                                        onCollection = openItem,
                                        onGenre = { id, name -> open(Detail(id, kind = "genre", title = name, section = t.section.key)) },
                                    )
                                    t == NavTab.Settings -> SetupScreen(vm, autoFocus)
                                }
                            }
                        }
                    }
                    AnimatedVisibility(top == null, enter = fadeIn(tween(250)), exit = fadeOut(tween(120))) {
                        SideRail(
                            entries = tabs.map { t ->
                                RailEntry(if (t is NavTab.Library) t.title else stringResource(t.titleRes), when (t) {
                                    NavTab.Search -> MasalIcons.Search
                                    NavTab.Home -> MasalIcons.Home
                                    NavTab.Tv -> MasalIcons.Tv
                                    is NavTab.Library -> if (t.section.type == "movie") MasalIcons.Film else MasalIcons.Series
                                    NavTab.Apps -> MasalIcons.Apps
                                    NavTab.Settings -> MasalIcons.Settings
                                })
                            },
                            selected = tabs.indexOf(tab).coerceAtLeast(0),
                            selectedRequester = railRequester,
                            onFocusEntry = { i -> railTarget = tabs[i] },
                            onEnter = {
                                val t = railTarget
                                railTarget = null // entering handles the pending switch; the delayed one must not repeat it
                                if (t != null && t != tab) selectTab(t, true) else enterContent()
                            },
                            onFocusChange = { railFocused = it },
                        )
                    }
                }
            }
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.alike.masal.data.Language.wrap(newBase))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        logKey(event)
        return super.dispatchKeyEvent(event)
    }

    public override fun onNewIntent(intent: Intent) { // public: the UI tests send the Home key
        super.onNewIntent(intent)
        // singleTask: the Home key brings this running instance back with a new intent.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homePresses++
    }

    override fun onResume() {
        super.onResume()
        isHome = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName == packageName
        // Back from the player or another app: refresh progress, history and app list.
        if (resumedOnce) vm.onResumeRefresh()
        resumedOnce = true
    }

    private fun playChannels(listKey: String, list: List<Channel>, index: Int, fromHome: Boolean = false) {
        // The TV tab and Search pass their lists' English keys; the player shows them in the app's language.
        val name = when (listKey) {
            "Last watched" -> getString(R.string.common_last_watched)
            "Favourites" -> getString(R.string.common_favourites)
            "Search" -> getString(R.string.common_search)
            else -> listKey
        }
        val ch = list.getOrNull(index)
        // The preview already playing this channel hands its stream over: no restart, no second connection.
        Playback.handedOver?.release()
        Playback.handedOver = ch?.let { Playback.takePreview("live:" + it.key) }
        Playback.stopPreviews()
        val all = vm.channels.value
        val byKey = all.associateBy { it.key }
        val recent = vm.recentChannels.value.mapNotNull { byKey[it] }
        val favs = all.filter { it.key in vm.favorites.value }
        Playback.groups = buildList {
            add(name to list)
            if (recent.isNotEmpty()) add(getString(R.string.common_last_watched) to recent)
            if (favs.isNotEmpty()) add(getString(R.string.common_favourites) to favs)
            addAll(all.groupBy { it.group }.toList())
        }.distinctBy { it.first }
        Playback.listName = name
        Playback.channels = list
        Playback.index = index
        Playback.epg = vm.epg.value
        val intent = Intent(this, PlayerActivity::class.java).putExtra("mode", "live").putExtra("fromHome", fromHome)
        // From Home the picture zooms from its frame to full screen (and back on exit, see PlayerActivity).
        val options = if (fromHome) android.app.ActivityOptions.makeCustomAnimation(
            this, R.anim.masal_zoom_in_from_frame, R.anim.masal_hold).toBundle() else null
        startActivity(intent, options)
    }

    private fun playPlex(item: PlexItem, fromStart: Boolean) {
        lifecycleScope.launch {
            val full = if (item.partKey != null) item else withContext(Dispatchers.IO) {
                runCatching { Graph.plex.metadata(item.ratingKey) }.getOrNull()
            }
            if (full?.partKey == null) {
                Toast.makeText(this@MainActivity, getString(R.string.common_no_playable_file), Toast.LENGTH_LONG).show()
                return@launch
            }
            val offset = if (fromStart) 0L else item.viewOffset.takeIf { it > 0 } ?: full.viewOffset
            Playback.plexItem = full.copy(viewOffset = offset)
            // Only now: if nothing can be played, trailers and live previews must keep working.
            Playback.stopPreviews()
            startActivity(Intent(this@MainActivity, PlayerActivity::class.java).putExtra("mode", "plex"))
        }
    }
}
