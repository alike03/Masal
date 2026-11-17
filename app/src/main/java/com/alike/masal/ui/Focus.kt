package com.alike.masal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/** Receives how a screen takes focus when the user leaves the side menu (returns false if it could not). */
val LocalEntryFocus = staticCompositionLocalOf<(() -> Boolean) -> Unit> { {} }

/** The last focused key; a plain field, so moving focus does not recompose the cards. Saved with the screen. */
class FocusKeyHolder(var key: String?)

private val FocusKeySaver = Saver<FocusKeyHolder, String>({ it.key.orEmpty() }, { FocusKeyHolder(it.ifEmpty { null }) })

/**
 * Remembers which item had focus on a screen, so coming back from a detail page, the player
 * or the side menu lands on the same card. Every card gets its own requester; when the saved
 * card no longer exists (removed by a refresh, row not composed), focus falls back to [defaultKey].
 */
class RestorableFocus(private val holder: FocusKeyHolder) {
    var defaultKey: String? = null
    private val requesters = HashMap<String, FocusRequester>()

    fun modifier(key: String): Modifier = Modifier
        .onFocusChanged { if (it.isFocused) holder.key = key }
        .focusRequester(requesters.getOrPut(key) { FocusRequester() })

    /** Focuses the default card (the first one); false when it is not composed. */
    fun focusDefault(): Boolean {
        val r = defaultKey?.let { requesters[it] } ?: return false
        return runCatching { r.requestFocus() }.isSuccess
    }

    /** Focuses the saved card, else the default one. */
    fun restore(): Boolean {
        for (k in listOfNotNull(holder.key, defaultKey).distinct()) {
            val r = requesters[k] ?: continue
            if (runCatching { r.requestFocus() }.isSuccess) return true
        }
        return false
    }
}

/** [autoFocus] is false when the screen was opened by moving along the tab bar. */
@Composable
fun rememberRestorableFocus(autoFocus: Boolean, ready: Boolean): RestorableFocus {
    val holder = rememberSaveable(saver = FocusKeySaver) { FocusKeyHolder(null) }
    val focus = remember { RestorableFocus(holder) }
    val entry = LocalEntryFocus.current
    LaunchedEffect(Unit) { entry { focus.restore() } }
    LaunchedEffect(ready) {
        if (autoFocus && ready) {
            delay(120)
            if (!focus.restore()) { delay(150); focus.restore() } // cards may still be composing
        }
    }
    return focus
}
