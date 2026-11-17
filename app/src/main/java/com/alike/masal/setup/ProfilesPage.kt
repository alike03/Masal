package com.alike.masal.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.R
import com.alike.masal.data.PlexHomeUser
import com.alike.masal.data.PlexProfile
import com.alike.masal.data.WrongPinException
import com.alike.masal.ui.Accent
import com.alike.masal.ui.MasalButton
import com.alike.masal.ui.Panel
import com.alike.masal.ui.TextDim
import com.alike.masal.ui.TextMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Profiles page shows: the list, one added profile's actions, or the PIN field for a member. */
internal sealed interface ProfilesView {
    data object List : ProfilesView
    data class Actions(val profile: PlexProfile) : ProfilesView
    data class Pin(val user: PlexHomeUser) : ProfilesView
}

/**
 * Settings, Profiles (profiles.md): the added profiles, then the Plex Home members not added yet.
 * OK on a member adds it (with its PIN where it has one); OK on a profile offers to sign in again
 * and to remove it. [onChanged] reloads the Plex rows.
 */
@Composable
internal fun ProfilesPage(view: ProfilesView, onView: (ProfilesView) -> Unit, onChanged: () -> Unit) {
    when (view) {
        ProfilesView.List -> ProfilesList(onView, onChanged)
        is ProfilesView.Actions -> ProfileActions(view.profile, onView, onChanged)
        is ProfilesView.Pin -> PinEditor(view.user, onCancel = { onView(ProfilesView.List) }) { onView(ProfilesView.List); onChanged() }
    }
}

@Composable
private fun ProfilesList(onView: (ProfilesView) -> Unit, onChanged: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val profiles = remember(version) { Graph.settings.profiles }
    val rejected = remember(version) { Graph.settings.profilesRejected }
    var members by remember { mutableStateOf<Result<List<PlexHomeUser>>?>(null) }
    LaunchedEffect(Unit) { members = withContext(Dispatchers.IO) { runCatching { Graph.plex.homeUsers() } } }
    val toAdd = members?.getOrNull().orEmpty().filter { m -> profiles.none { it.id == m.id } }
    var adding by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Column(Modifier.widthIn(max = 640.dp)) {
        Text(stringResource(R.string.settings_profiles), color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.settings_profiles_note), color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        error?.let { Text(it, color = Accent, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(profiles, key = { "p:" + it.id }) { p ->
                val detail = stringResource(if (p.id in rejected) R.string.settings_profile_signed_out else R.string.settings_profile_added)
                OptionRow(p.name, detail, checked = true, if (p == profiles.first()) Modifier.focusRequester(first) else Modifier) {
                    onView(ProfilesView.Actions(p))
                }
            }
            item { SubHeading(stringResource(R.string.settings_profiles_home)) }
            val m = members
            when {
                m == null -> item { Text(stringResource(R.string.settings_profiles_loading), color = TextDim, fontSize = 15.sp) }
                m.isFailure -> item { Text(stringResource(R.string.settings_profiles_failed), color = TextDim, fontSize = 15.sp) }
                toAdd.isEmpty() -> item { Text(stringResource(R.string.settings_profiles_none), color = TextDim, fontSize = 15.sp) }
            }
            items(toAdd, key = { "m:" + it.id }) { u ->
                val detail = when {
                    adding == u.id -> stringResource(R.string.settings_profile_adding)
                    u.protected -> stringResource(R.string.settings_profile_pin)
                    else -> stringResource(R.string.settings_profile_add)
                }
                OptionRow(u.name, detail, checked = false, if (profiles.isEmpty() && u == toAdd.first()) Modifier.focusRequester(first) else Modifier) {
                    if (adding != null) return@OptionRow
                    if (u.protected) { onView(ProfilesView.Pin(u)); return@OptionRow }
                    adding = u.id; error = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { Graph.plex.profileToken(u.id, null) } }
                        adding = null
                        r.onSuccess { token -> Graph.settings.saveProfile(PlexProfile(u.id, u.name, token)); version++; onChanged() }
                            .onFailure { error = context.getString(R.string.settings_profile_failed) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileActions(profile: PlexProfile, onView: (ProfilesView) -> Unit, onChanged: () -> Unit) {
    val rejected = remember { profile.id in Graph.settings.profilesRejected }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Column(Modifier.widthIn(max = 640.dp)) {
        Text(profile.name, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(if (rejected) R.string.settings_profile_signed_out_note else R.string.settings_profile_note),
            color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (rejected) {
                MasalButton(stringResource(R.string.settings_profile_sign_in), {
                    // Signing in again goes through the PIN field; a member without a PIN gets an empty field.
                    onView(ProfilesView.Pin(PlexHomeUser(profile.id, profile.name, protected = true)))
                }, Modifier.focusRequester(first))
            }
            MasalButton(stringResource(R.string.settings_profile_remove), {
                Graph.settings.removeProfile(profile.id)
                onView(ProfilesView.List)
                onChanged()
            }, if (rejected) Modifier else Modifier.focusRequester(first), primary = rejected.not())
            MasalButton(stringResource(R.string.settings_cancel), { onView(ProfilesView.List) }, primary = false)
        }
    }
}

/** The member's PIN on the TV keyboard; used once to sign in, never stored. */
@Composable
private fun PinEditor(user: PlexHomeUser, onCancel: () -> Unit, onAdded: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() }; keyboard?.show() }
    fun submit() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Graph.plex.profileToken(user.id, pin.ifBlank { null }) } }
            busy = false
            r.onSuccess { token -> Graph.settings.saveProfile(PlexProfile(user.id, user.name, token)); onAdded() }
                .onFailure {
                    pin = ""
                    error = context.getString(if (it is WrongPinException) R.string.settings_profile_wrong_pin else R.string.settings_profile_failed)
                    runCatching { field.requestFocus() }; keyboard?.show()
                }
        }
    }
    Column(Modifier.widthIn(max = 640.dp)) {
        Text(user.name, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.settings_profile_pin_note), color = TextDim, fontSize = 15.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        BasicTextField(
            value = pin,
            onValueChange = { v -> pin = v.filter { it.isDigit() }.take(8) },
            singleLine = true,
            textStyle = TextStyle(color = TextMain, fontSize = 24.sp, letterSpacing = 8.sp),
            cursorBrush = SolidColor(Accent),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.focusRequester(field).fillMaxWidth()
                .background(Panel, RoundedCornerShape(10.dp))
                .border(1.dp, Accent, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
        error?.let { Text(it, color = Accent, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MasalButton(stringResource(if (busy) R.string.settings_profile_adding else R.string.settings_profile_sign_in), { submit() })
            MasalButton(stringResource(R.string.settings_cancel), onCancel, primary = false)
        }
    }
}

@Composable
private fun SubHeading(text: String) {
    Text(text, color = TextDim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}
