package com.alike.masal.setup

import android.content.Context
import android.os.Build
import com.alike.masal.Graph
import com.alike.masal.R
import com.alike.masal.data.HomeRowOption
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Keeps the setup page running while Masal's process lives (setup.md): started once by
 * MainActivity, never by a background service. Idle it is one thread waiting on the port.
 */
object SetupHost {
    private var server: SetupServer? = null

    /** Filled in by MainActivity from its view model; the page asks them on every load. */
    @Volatile var channelGroups: () -> List<String> = { emptyList() }
    @Volatile var homeRows: () -> List<HomeRowOption> = { emptyList() }
    /** Home's rows in their order, as (id, name) (home.md, "Row order"). */
    @Volatile var homeOrder: () -> List<Pair<String, String>> = { emptyList() }
    /** Moves a channel category by one place; null resets to the provider's order. */
    @Volatile var moveCategory: (String?, Int) -> Unit = { _, _ -> }

    /** Why the page could not start (port taken), for Settings; null while it runs. */
    val error = MutableStateFlow<String?>(null)

    @Synchronized
    fun start(context: Context) {
        // The JVM tests (Robolectric) start MainActivity many times: no real port there.
        if (server != null || Build.FINGERPRINT == "robolectric") return
        val app = context.applicationContext
        fun res(id: Int) = runCatching { app.resources.openRawResource(id).use { it.readBytes() } }.getOrNull()
        val assets = listOfNotNull(
            res(R.raw.favicon)?.let { "favicon.png" to ("image/png" to it) },
            res(R.drawable.masal_mark)?.let { "logo.png" to ("image/png" to it) },
            res(R.font.dm_sans)?.let { "dm-sans.ttf" to ("font/ttf" to it) },
            res(R.font.fraunces)?.let { "fraunces.ttf" to ("font/ttf" to it) },
        ).toMap()
        val s = SetupServer(
            app, Graph.settings, SetupAuth(Graph.settings), { Graph.settings.changed() }, assets,
            channelGroups = { channelGroups() },
            homeRows = { homeRows() },
            homeOrder = { homeOrder() },
            moveCategory = { name, delta -> moveCategory(name, delta) },
        )
        runCatching { s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            .onSuccess { server = s; error.value = null }
            .onFailure { error.value = app.getString(R.string.settings_server_error, it.message.toString()) }
    }
}
