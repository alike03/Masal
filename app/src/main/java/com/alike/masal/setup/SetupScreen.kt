package com.alike.masal.setup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.MasalViewModel
import com.alike.masal.R
import com.alike.masal.data.Apps
import com.alike.masal.data.HomeRowOption
import com.alike.masal.data.Language
import com.alike.masal.ui.Accent
import com.alike.masal.ui.EdgePadding
import com.alike.masal.ui.LocalEntryFocus
import com.alike.masal.ui.MasalButton
import com.alike.masal.ui.NavHeight
import com.alike.masal.ui.OnAccent
import com.alike.masal.ui.Panel
import com.alike.masal.ui.TextDim
import com.alike.masal.ui.TextMain
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class SettingsPage { Main, Language, Category, Title, Order, Profiles }

/**
 * Settings on the TV: the QR code for the phone page (logins, sources, Plex server) on the left,
 * and the settings that work with the remote on the right (spec: docs/specs/setup.md).
 */
@Composable
fun SetupScreen(vm: MasalViewModel, autoFocus: Boolean) {
    // The page and the Plex link poll run only while the app is in the foreground; after Home or
    // standby the old address stops working and a new one is shown on return.
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val running = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    val key = remember(running) { SetupServer.randomKey() }
    val ip = remember { SetupServer.localIp() }
    val url = "http://$ip:$SETUP_PORT/$key"
    val qr = remember(url) { SetupServer.qr(url, 420).asImageBitmap() }
    var serverError by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // A language picked on the phone page is only stored; the TV follows when Settings opens again.
    LaunchedEffect(running) {
        if (running && SetupServer.texts(context).getString(R.string.setup_lang_code) != context.getString(R.string.setup_lang_code)) {
            context.findActivity()?.recreate()
        }
    }

    DisposableEffect(key, running) {
        if (!running) return@DisposableEffect onDispose { }
        fun res(id: Int) = runCatching { context.resources.openRawResource(id).use { it.readBytes() } }.getOrNull()
        val assets = listOfNotNull(
            res(com.alike.masal.R.raw.favicon)?.let { "favicon.png" to ("image/png" to it) },
            res(com.alike.masal.R.drawable.masal_mark)?.let { "logo.png" to ("image/png" to it) },
            res(com.alike.masal.R.font.dm_sans)?.let { "dm-sans.ttf" to ("font/ttf" to it) },
            res(com.alike.masal.R.font.fraunces)?.let { "fraunces.ttf" to ("font/ttf" to it) },
        ).toMap()
        val server = SetupServer(
            context, Graph.settings, key, { Graph.settings.changed() }, assets,
            channelGroups = { channelGroups(vm) },
            homeRows = { vm.homeRowOptions.value },
        )
        runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            .onFailure { serverError = context.getString(R.string.settings_server_error, it.message.toString()) }
        onDispose { server.stop() }
    }

    // Plex link code: the user enters it at plex.tv/link, no token copying needed.
    var plexStatus by remember {
        mutableStateOf(context.getString(if (Graph.plex.configured) R.string.settings_plex_connected else R.string.settings_plex_requesting))
    }
    var plexCode by remember { mutableStateOf<String?>(null) }
    var linkRequest by remember { mutableStateOf(if (Graph.plex.configured) 0 else 1) }
    LaunchedEffect(linkRequest, running) {
        if (linkRequest == 0 || !running) return@LaunchedEffect
        plexStatus = context.getString(R.string.settings_plex_requesting)
        withContext(Dispatchers.IO) {
            val pin = runCatching { Graph.plex.createPin() }.getOrElse {
                plexStatus = context.getString(R.string.settings_plex_unreachable, it.message.toString()); return@withContext
            }
            plexCode = pin.code
            plexStatus = context.getString(R.string.settings_plex_waiting)
            val until = System.currentTimeMillis() + 15 * 60_000
            while (System.currentTimeMillis() < until) {
                delay(2_000)
                val token = runCatching { Graph.plex.checkPin(pin) }.getOrNull() ?: continue
                plexStatus = context.getString(R.string.settings_plex_searching)
                Graph.settings.plexToken = token // keep the link even if the server lookup fails
                val server = if (Graph.settings.plexUrl.isBlank()) runCatching { Graph.plex.findServerUrl(token) }.getOrNull()
                    else Graph.settings.plexUrl
                if (server != null) Graph.settings.plexUrl = server
                Graph.settings.changed()
                plexCode = null
                plexStatus = if (server != null) context.getString(R.string.settings_plex_connected_server, server)
                    else context.getString(R.string.settings_plex_no_server)
                return@withContext
            }
            plexCode = null
            plexStatus = context.getString(R.string.settings_plex_expired)
        }
    }

    val version by Graph.settings.version.collectAsState()
    val homeVersion by Graph.settings.homeVersion.collectAsState()
    val sources = remember(version) { Graph.settings.sources }
    val options by vm.homeRowOptions.collectAsState()
    val channels by vm.channels.collectAsState()
    val groups = remember(channels) { channelGroups(vm) }
    val liveGroup = remember(version, homeVersion) { Graph.settings.homeLiveGroup }
    val liveTitle = remember(version, homeVersion) { Graph.settings.homeLiveTitle }

    var page by remember { mutableStateOf(SettingsPage.Main) }
    // The entry that opened a sub-page gets the focus back when it closes.
    var back by remember { mutableStateOf<FocusRequester?>(null) }
    val categoryRow = remember { FocusRequester() }
    val titleRow = remember { FocusRequester() }
    val languageRow = remember { FocusRequester() }
    val orderRow = remember { FocusRequester() }
    val profilesRow = remember { FocusRequester() }
    var profilesView by remember { mutableStateOf<ProfilesView>(ProfilesView.List) }
    val categoryOrder by vm.categoryOrder.collectAsState()
    // The category being moved on the order page; Back then puts it down instead of leaving the page.
    var moving by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()

    val done = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) { delay(150); runCatching { done.requestFocus() } } }
    // Leaving the side menu: the first button while the list is at its top (otherwise the nearest entry).
    val entry = LocalEntryFocus.current
    LaunchedEffect(entry) {
        entry { page == SettingsPage.Main && list.firstVisibleItemIndex <= 2 && runCatching { done.requestFocus() }.isSuccess }
    }

    Row(
        Modifier.fillMaxSize().padding(start = EdgePadding, end = EdgePadding, top = NavHeight + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Column(Modifier.width(240.dp)) {
            Box(Modifier.size(220.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(10.dp)) {
                Image(qr, contentDescription = stringResource(R.string.settings_qr_description), modifier = Modifier.fillMaxSize())
            }
            Text(url, color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Text(
                stringResource(R.string.settings_qr_hint),
                color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp),
            )
            serverError?.let { Text(it, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
        // Back in a sub-page returns to the list. Handled on the key itself (like the TV tab), so the
        // key never reaches the side menu's Back handling.
        Box(Modifier.weight(1f).onPreviewKeyEvent { e ->
            if (page == SettingsPage.Main || e.key != Key.Back) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyUp) when {
                moving != null -> moving = null
                page == SettingsPage.Profiles && profilesView != ProfilesView.List -> profilesView = ProfilesView.List
                else -> page = SettingsPage.Main
            }
            true
        }) {
            when (page) {
                SettingsPage.Main -> {
                    LaunchedEffect(Unit) {
                        val r = back ?: return@LaunchedEffect
                        back = null
                        // Two frames: the list's rows are composed during the first one.
                        repeat(2) { androidx.compose.runtime.withFrameNanos { } }
                        runCatching { r.requestFocus() }
                    }
                    MainList(
                        list, done, categoryRow, titleRow, languageRow, orderRow,
                        plexCode, plexStatus, onLink = { linkRequest++ },
                        onAndroidSettings = { Apps.openSystemSettings(context) },
                        liveGroup = liveGroup, liveTitle = liveTitle,
                        onLanguage = { back = languageRow; page = SettingsPage.Language },
                        onCategory = { back = categoryRow; page = SettingsPage.Category },
                        onTitle = { back = titleRow; page = SettingsPage.Title },
                        orderValue = stringResource(if (categoryOrder.isEmpty()) R.string.settings_category_order_default else R.string.settings_category_order_custom),
                        onOrder = { back = orderRow; moving = null; page = SettingsPage.Order },
                        profilesRow = profilesRow,
                        onProfiles = { back = profilesRow; profilesView = ProfilesView.List; page = SettingsPage.Profiles },
                        options = options,
                        onToggle = { o ->
                            Graph.settings.setHomeRowShown(o, !Graph.settings.homeRowShown(o))
                            Graph.settings.homeChanged()
                        },
                        sourceNames = sources.map { it.name },
                    )
                }
                SettingsPage.Language -> {
                    val device = stringResource(R.string.language_device)
                    val choices = Language.choices.map { (code, name) -> code to (name ?: device) }
                    val current = Language.stored(context)
                    ChoiceList(
                        stringResource(R.string.language_title), stringResource(R.string.language_note),
                        choices, selected = choices.indexOfFirst { it.first == current },
                    ) { code ->
                        page = SettingsPage.Main
                        // Redraws the activity in the new language; an unchanged choice just returns.
                        context.findActivity()?.let { Language.set(it, code) }
                    }
                }
                SettingsPage.Category -> {
                    val savedNorm = MasalViewModel.normGroup(liveGroup)
                    val choices = listOf("" to stringResource(R.string.settings_category_none)) +
                        (if (liveGroup.isNotBlank() && groups.none { MasalViewModel.normGroup(it) == savedNorm })
                            listOf(liveGroup to stringResource(R.string.settings_category_missing, liveGroup)) else emptyList()) +
                        groups.map { it to it }
                    ChoiceList(
                        stringResource(R.string.settings_channel_category), stringResource(R.string.settings_category_note),
                        choices,
                        selected = choices.indexOfFirst { (g, _) -> if (g.isBlank()) liveGroup.isBlank() else liveGroup.isNotBlank() && MasalViewModel.normGroup(g) == savedNorm },
                    ) { g ->
                        Graph.settings.homeLiveGroup = g
                        Graph.settings.homeChanged()
                        page = SettingsPage.Main
                    }
                }
                SettingsPage.Order -> CategoryOrderList(
                    names = remember(channels, categoryOrder) { channelGroups(vm) },
                    custom = categoryOrder.isNotEmpty(),
                    moving = moving,
                    onPick = { moving = if (moving == it) null else it },
                    onMove = { name, delta -> vm.moveCategory(name, delta) },
                    onReset = { moving = null; vm.setCategoryOrder(emptyList()) },
                )
                SettingsPage.Profiles -> ProfilesPage(profilesView, { profilesView = it }) { vm.refreshPlex() }
                SettingsPage.Title -> TitleEditor(liveTitle, onCancel = { page = SettingsPage.Main }) { t ->
                    Graph.settings.homeLiveTitle = t
                    Graph.settings.homeChanged()
                    page = SettingsPage.Main
                }
            }
        }
    }
}

/** The activity behind a (possibly wrapped) context. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Channel categories of the loaded sources, in provider order. */
private fun channelGroups(vm: MasalViewModel) = vm.categoryNames()

@Composable
private fun MainList(
    list: LazyListState,
    first: FocusRequester,
    categoryRow: FocusRequester,
    titleRow: FocusRequester,
    languageRow: FocusRequester,
    orderRow: FocusRequester,
    plexCode: String?,
    plexStatus: String,
    onLink: () -> Unit,
    onAndroidSettings: () -> Unit,
    liveGroup: String,
    liveTitle: String,
    onLanguage: () -> Unit,
    onCategory: () -> Unit,
    onTitle: () -> Unit,
    orderValue: String,
    onOrder: () -> Unit,
    profilesRow: FocusRequester,
    onProfiles: () -> Unit,
    options: List<HomeRowOption>,
    onToggle: (HomeRowOption) -> Unit,
    sourceNames: List<String>,
) {
    val homeVersion by Graph.settings.homeVersion.collectAsState() // check marks follow every change
    val context = androidx.compose.ui.platform.LocalContext.current
    val language = Language.stored(context).let { code ->
        Language.choices.firstOrNull { it.first == code }?.second ?: stringResource(R.string.language_device)
    }
    val categoryNone = stringResource(R.string.settings_category_none)
    LazyColumn(
        state = list,
        modifier = Modifier.widthIn(max = 640.dp),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text(stringResource(R.string.settings_title), color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold) }

        item { Heading("Plex") }
        item {
            Column {
                plexCode?.let { code ->
                    Text(stringResource(R.string.settings_plex_open_link), color = TextDim, fontSize = 16.sp)
                    Text(code, color = Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                }
                Text(plexStatus, color = TextDim, fontSize = 15.sp)
                Row(Modifier.padding(top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MasalButton(stringResource(if (Graph.plex.configured) R.string.settings_plex_link_again else R.string.settings_plex_new_code), onLink,
                        Modifier.focusRequester(first), Icons.Filled.Refresh)
                    // Network, display, apps: reachable from here when Masal replaces the stock home screen.
                    MasalButton(stringResource(R.string.settings_android_settings), onAndroidSettings, icon = Icons.Filled.Settings, primary = false)
                }
            }
        }
        if (Graph.plex.configured) item {
            val names = remember(homeVersion) { Graph.settings.profiles.joinToString(", ") { it.name } }
            SettingRow(stringResource(R.string.settings_profiles), names.ifBlank { stringResource(R.string.settings_profiles_value_none) },
                onProfiles, Modifier.focusRequester(profilesRow))
        }

        item { Heading(stringResource(R.string.language_title)) }
        item { SettingRow(stringResource(R.string.language_title), language, onLanguage, Modifier.focusRequester(languageRow)) }

        item { Heading(stringResource(R.string.settings_live_row_heading)) }
        item { SettingRow(stringResource(R.string.settings_channel_category), liveGroup.ifBlank { categoryNone }, onCategory, Modifier.focusRequester(categoryRow)) }
        item { SettingRow(stringResource(R.string.settings_row_title), liveTitle, onTitle, Modifier.focusRequester(titleRow)) }

        item { Heading(stringResource(R.string.settings_tv_tab_heading)) }
        item { SettingRow(stringResource(R.string.settings_category_order), orderValue, onOrder, Modifier.focusRequester(orderRow)) }

        item { Heading(stringResource(R.string.settings_home_rows)) }
        if (options.isEmpty()) {
            item { Text(stringResource(R.string.settings_home_rows_empty), color = TextDim, fontSize = 15.sp) }
        }
        options.groupBy { it.group }.forEach { (group, rows) ->
            item(key = "group:$group") { Text(group, color = TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) }
            items(rows, key = { "row:" + it.id }) { o ->
                val shown = remember(homeVersion, o) { Graph.settings.homeRowShown(o) }
                OptionRow(o.title, null, shown) { onToggle(o) }
            }
        }

        item { Heading(stringResource(R.string.settings_sources_heading)) }
        item {
            Column {
                if (sourceNames.isEmpty()) Text(stringResource(R.string.settings_sources_none), color = TextDim, fontSize = 15.sp)
                sourceNames.forEach { Text(it, color = TextMain, fontSize = 15.sp, modifier = Modifier.padding(top = 2.dp)) }
                Text(stringResource(R.string.settings_sources_hint), color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = TextMain, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
}

/** A setting with its current value; OK opens where it is changed. */
@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListSurface(onClick, modifier) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(value, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
        }
    }
}

/** A choice with a check mark when it is on (or picked). */
@Composable
internal fun OptionRow(label: String, detail: String?, checked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListSurface(onClick, modifier) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(30.dp)) {
                if (checked) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.settings_check_on), modifier = Modifier.size(20.dp))
            }
            Column {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrBlank()) Text(detail, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ListSurface(onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Panel, contentColor = TextMain,
            focusedContainerColor = TextMain, focusedContentColor = OnAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) { content() }
}

/** Pick one of [choices] (value to label); OK picks, Back leaves it unchanged. */
@Composable
private fun ChoiceList(title: String, note: String, choices: List<Pair<String, String>>, selected: Int, onPick: (String) -> Unit) {
    val target = selected.coerceAtLeast(0)
    val first = remember { FocusRequester() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (target - 2).coerceAtLeast(0))
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Column(Modifier.widthIn(max = 640.dp)) {
        Text(title, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(note, color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(choices) { i, (value, label) ->
                OptionRow(label, null, i == selected, if (i == target) Modifier.focusRequester(first) else Modifier) { onPick(value) }
            }
        }
    }
}

/**
 * The channel categories in the TV tab's order. OK picks one up, Up/Down move it, OK or Back put
 * it down (no long press over HDMI-CEC). Every move is stored at once.
 */
@Composable
private fun CategoryOrderList(
    names: List<String>,
    custom: Boolean,
    moving: String?,
    onPick: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onReset: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    // The moved category stays in view (rows below the title and the reset button: offset 1).
    LaunchedEffect(names, moving) {
        val i = names.indexOf(moving ?: return@LaunchedEffect)
        if (i < 0) return@LaunchedEffect
        val visible = list.layoutInfo.visibleItemsInfo.map { it.index }
        if (i + 1 !in visible.drop(1).dropLast(1)) list.animateScrollToItem((i - 2).coerceAtLeast(0))
    }
    Column(Modifier.widthIn(max = 640.dp)) {
        Text(stringResource(R.string.settings_category_order), color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.settings_category_order_note), color = TextDim, fontSize = 15.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        if (names.isEmpty()) {
            Text(stringResource(R.string.settings_category_order_empty), color = TextDim, fontSize = 15.sp)
            return@Column
        }
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "reset") {
                if (custom) MasalButton(stringResource(R.string.settings_category_order_reset), onReset,
                    Modifier.padding(bottom = 8.dp), Icons.Filled.Refresh, primary = false)
            }
            itemsIndexed(names, key = { _, n -> "cat:$n" }) { i, name ->
                val isMoving = name == moving
                ListSurface(
                    onClick = { onPick(name) },
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).onPreviewKeyEvent { e ->
                        if (!isMoving || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionUp -> { onMove(name, -1); true }
                            Key.DirectionDown -> { onMove(name, +1); true }
                            Key.DirectionLeft, Key.DirectionRight -> true // stay on the moved row
                            else -> false
                        }
                    },
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontSize = 13.sp, modifier = Modifier.width(36.dp))
                        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        if (isMoving) Text("▲ ▼  " + stringResource(R.string.settings_category_order_moving), fontSize = 13.sp, color = Accent)
                    }
                }
            }
        }
    }
}

/** Text field for the row title; the TV keyboard opens with it. Done or Save stores, Back cancels. */
@Composable
private fun TitleEditor(initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() }; keyboard?.show() }
    Column(Modifier.widthIn(max = 640.dp)) {
        Text(stringResource(R.string.settings_row_title), color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.settings_title_note), color = TextDim, fontSize = 15.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = TextStyle(color = TextMain, fontSize = 20.sp),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text.text) }),
            modifier = Modifier.focusRequester(field).fillMaxWidth()
                .background(Panel, RoundedCornerShape(10.dp))
                .border(1.dp, Accent, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MasalButton(stringResource(R.string.settings_save), { onSave(text.text) })
            MasalButton(stringResource(R.string.settings_cancel), onCancel, primary = false)
        }
    }
}
