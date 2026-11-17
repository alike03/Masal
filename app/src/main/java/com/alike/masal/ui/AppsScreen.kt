package com.alike.masal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.alike.masal.MasalViewModel
import com.alike.masal.R
import com.alike.masal.data.TvApp

/**
 * All installed apps. "Edit Home apps" switches OK from opening an app to pinning it
 * on Home; in that mode OK on a Home app picks it up, ◀ ▶ moves it, OK puts it down.
 * Without edit mode, ▶❚❚ (or Menu) pins or unpins the focused app.
 */
@Composable
fun AppsScreen(vm: MasalViewModel, autoFocus: Boolean, startEditing: Boolean, onApp: (TvApp) -> Unit) {
    val all by vm.apps.collectAsState()
    val homeKeys by vm.homeApps.collectAsState()
    val backdrops by vm.backdrops.collectAsState()
    val home = remember(all, homeKeys) { val byPkg = all.associateBy { it.pkg }; homeKeys.mapNotNull { byPkg[it] } }
    var editing by rememberSaveable { mutableStateOf(startEditing) }
    var moving by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf<TvApp?>(null) }
    val focus = rememberRestorableFocus(autoFocus, all.isNotEmpty())
    focus.defaultKey = all.firstOrNull()?.let { "all:${it.pkg}" }

    val bg = (focused ?: all.firstOrNull())?.let { backdropFor(it.pkg, backdrops) }
    ReportAmbient(bg?.let { Ambient("app:" + it.url, it.url) })

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = rememberLazyGridState(),
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = EdgePadding, end = EdgePadding, top = 26.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
                // Title (and, while editing, how it works) on the left, the edit button on the right edge.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.appspage_title), color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        val hint = when {
                            moving != null -> R.string.appspage_hint_moving
                            editing -> R.string.appspage_hint_editing
                            else -> null
                        }
                        if (hint != null) Text(stringResource(hint), color = TextDim, fontSize = 13.sp)
                    }
                    MasalButton(
                        stringResource(if (editing) R.string.appspage_done else R.string.appspage_edit),
                        { editing = !editing; moving = null },
                        focus.modifier("edit"),
                        icon = if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                        primary = editing,
                    )
                }
            }
            if (home.isNotEmpty()) {
                item(key = "homeTitle", span = { GridItemSpan(maxLineSpan) }) {
                    Text(stringResource(R.string.appspage_on_home), color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                }
                item(key = "homeRow", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.onPreviewKeyEvent { e ->
                            val m = moving ?: return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionLeft, Key.DirectionRight -> {
                                    if (e.type == KeyEventType.KeyDown) vm.moveHomeApp(m, if (e.key == Key.DirectionLeft) -1 else 1)
                                    true
                                }
                                Key.DirectionCenter, Key.Enter, Key.Back -> { if (e.type == KeyEventType.KeyUp) moving = null; true }
                                else -> true
                            }
                        },
                    ) {
                        items(home, key = { "home:" + it.pkg }) { app ->
                            AppCard(
                                app,
                                onClick = { if (editing) moving = app.pkg else onApp(app) },
                                onFocus = { focused = app },
                                cardModifier = focus.modifier("home:${app.pkg}").onPreviewKeyEvent { e ->
                                    if (e.key in MoveKeys && moving == null) { if (e.type == KeyEventType.KeyUp) moving = app.pkg; true } else false
                                },
                                moving = moving == app.pkg,
                            )
                        }
                    }
                }
            }
            item(key = "allTitle", span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.appspage_all_apps), color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            }
            items(all, key = { "all:" + it.pkg }) { app ->
                AppCard(
                    app,
                    onClick = { if (editing) vm.toggleHomeApp(app.pkg) else onApp(app) },
                    onFocus = { focused = app },
                    cardModifier = focus.modifier("all:${app.pkg}").onPreviewKeyEvent { e ->
                        if (e.key in MoveKeys) { if (e.type == KeyEventType.KeyUp) vm.toggleHomeApp(app.pkg); true } else false
                    },
                    pinned = app.pkg in homeKeys,
                )
            }
        }
    }
}
