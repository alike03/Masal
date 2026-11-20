package com.alike.masal.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.R
import com.alike.masal.data.SeerrUser
import com.alike.masal.ui.Accent
import com.alike.masal.ui.MasalButton
import com.alike.masal.ui.TextDim
import com.alike.masal.ui.TextMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Settings, Seerr (seerr.md): who it is connected as, the Seerr user each profile requests as,
 * and "Clear cache". Address and key are entered on the phone page. [pick] is the profile whose
 * user is being chosen ("" = main account), null for the overview.
 */
@Composable
internal fun SeerrPage(pick: String?, onPick: (String?) -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val users by produceState<Result<List<SeerrUser>>?>(null) {
        value = if (Graph.seerr.configured) withContext(Dispatchers.IO) { runCatching { Graph.seerr.users() } } else null
    }
    if (pick != null) {
        val list = users?.getOrNull().orEmpty()
        val auto = Graph.seerr.matchUser(pick, list)?.name
        val choices = listOf("" to (auto?.let { stringResource(R.string.settings_seerr_auto_user, it) } ?: stringResource(R.string.settings_seerr_auto))) +
            list.map { it.id.toString() to it.name }
        val current = Graph.settings.seerrUsers[pick]?.first?.toString().orEmpty()
        ChoiceList(profileTitle(pick), stringResource(R.string.settings_seerr_pick_note), choices, choices.indexOfFirst { it.first == current }) { id ->
            val chosen = list.firstOrNull { it.id.toString() == id }
            Graph.settings.seerrUsers = if (chosen == null) Graph.settings.seerrUsers - pick
                else Graph.settings.seerrUsers + (pick to (chosen.id to chosen.name))
            version++
            onPick(null)
        }
        return
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    var message by remember { mutableStateOf<String?>(null) }
    val cleared = stringResource(R.string.settings_seerr_cache_cleared)
    Column(Modifier.widthIn(max = 640.dp)) {
        Text("Seerr", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (!Graph.seerr.configured) {
            Text(stringResource(R.string.settings_seerr_setup_note), color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
            // Something to focus, so Back and the side menu behave as on the other pages.
            MasalButton(stringResource(R.string.settings_seerr_clear_cache), { Graph.seerr.cache.clear(); message = cleared },
                Modifier.padding(top = 16.dp).focusRequester(first), primary = false)
            message?.let { Text(it, color = Accent, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
            return@Column
        }
        Text(stringResource(R.string.settings_seerr_connected, Graph.settings.seerrUserName), color = TextDim, fontSize = 15.sp,
            modifier = Modifier.padding(top = 4.dp))
        Text(stringResource(R.string.settings_seerr_note), color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Heading(stringResource(R.string.settings_seerr_request_as))
        when {
            users == null -> Text(stringResource(R.string.settings_seerr_users_loading), color = TextDim, fontSize = 15.sp)
            users?.isFailure == true -> Text(stringResource(R.string.settings_seerr_users_failed), color = TextDim, fontSize = 15.sp)
        }
        val keys = listOf("") + Graph.settings.profiles.map { it.id }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(keys, key = { "u:$it" }) { k ->
                val chosen = remember(version) { Graph.settings.seerrUsers[k]?.second }
                val auto = users?.getOrNull()?.let { Graph.seerr.matchUser(k, it)?.name }
                val value = chosen ?: auto?.let { stringResource(R.string.settings_seerr_auto_user, it) } ?: stringResource(R.string.settings_seerr_auto)
                SettingRow(profileTitle(k), value, { if (users?.isSuccess == true) onPick(k) },
                    if (k.isEmpty()) Modifier.focusRequester(first) else Modifier)
            }
        }
        MasalButton(stringResource(R.string.settings_seerr_clear_cache), { Graph.seerr.cache.clear(); message = cleared }, primary = false)
        message?.let { Text(it, color = Accent, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}
