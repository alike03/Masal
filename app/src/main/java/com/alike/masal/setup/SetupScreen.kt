package com.alike.masal.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.alike.masal.ui.MasalButton
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.ui.Accent
import com.alike.masal.ui.EdgePadding
import com.alike.masal.ui.NavHeight
import com.alike.masal.ui.TextDim
import com.alike.masal.ui.TextMain
import androidx.lifecycle.compose.currentStateAsState
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SetupScreen(autoFocus: Boolean) {
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

    DisposableEffect(key, running) {
        if (!running) return@DisposableEffect onDispose { }
        val icon = runCatching { context.resources.openRawResource(com.alike.masal.R.raw.favicon).use { it.readBytes() } }.getOrNull()
        val server = SetupServer(Graph.settings, key, { Graph.settings.changed() }, icon)
        runCatching { server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            .onFailure { serverError = "Setup page could not start: ${it.message}" }
        onDispose { server.stop() }
    }

    // Plex link code: the user enters it at plex.tv/link, no token copying needed.
    var plexStatus by remember {
        mutableStateOf(if (Graph.plex.configured) "Plex is connected." else "Requesting a Plex link code")
    }
    var plexCode by remember { mutableStateOf<String?>(null) }
    var linkRequest by remember { mutableStateOf(if (Graph.plex.configured) 0 else 1) }
    LaunchedEffect(linkRequest, running) {
        if (linkRequest == 0 || !running) return@LaunchedEffect
        plexStatus = "Requesting a Plex link code"
        withContext(Dispatchers.IO) {
            val pin = runCatching { Graph.plex.createPin() }.getOrElse {
                plexStatus = "plex.tv not reachable: ${it.message}"; return@withContext
            }
            plexCode = pin.code
            val until = System.currentTimeMillis() + 15 * 60_000
            while (System.currentTimeMillis() < until) {
                delay(2_000)
                val token = runCatching { Graph.plex.checkPin(pin) }.getOrNull() ?: continue
                plexStatus = "Linked. Looking for your server"
                Graph.settings.plexToken = token // keep the link even if the server lookup fails
                val server = if (Graph.settings.plexUrl.isBlank()) runCatching { Graph.plex.findServerUrl(token) }.getOrNull()
                    else Graph.settings.plexUrl
                if (server != null) Graph.settings.plexUrl = server
                Graph.settings.changed()
                plexCode = null
                plexStatus = if (server != null) "Plex is connected ($server)." else "Linked, but no server found. Enter the server URL on the setup page."
                return@withContext
            }
            plexCode = null
            plexStatus = "The link code expired. Open Settings again for a new one."
        }
    }

    val version by Graph.settings.version.collectAsState()
    val sources = remember(version) { Graph.settings.sources }
    val done = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) { delay(150); runCatching { done.requestFocus() } } }

    Row(Modifier.fillMaxSize().padding(start = EdgePadding, end = EdgePadding, top = NavHeight + 16.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column {
            Box(Modifier.size(260.dp).background(Color.White, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Image(qr, contentDescription = "QR code for the setup page", modifier = Modifier.fillMaxSize())
            }
            Text(url, color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Settings", color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Scan the code with your phone (same Wi-Fi) to add IPTV sources or Plex details. " +
                    "Changes show up here right away.",
                color = TextDim, fontSize = 17.sp,
            )
            serverError?.let { Text(it, color = Accent, modifier = Modifier.padding(top = 8.dp)) }

            Spacer(Modifier.height(18.dp))
            Text("Plex", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            plexCode?.let { code ->
                Text("Open plex.tv/link and enter", color = TextDim, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
                Text(code, color = Accent, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
            }
            Text(plexStatus, color = TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(18.dp))
            Text("IPTV sources", color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (sources.isEmpty()) Text("None yet", color = TextDim, fontSize = 15.sp)
            sources.forEach { s ->
                Text("${s.name}  (${if (s.type == "xtream") "Xtream" else "M3U"})", color = TextDim, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(20.dp))
            Row {
                MasalButton(
                    if (Graph.plex.configured) "Link Plex again" else "New Plex code",
                    { linkRequest++ }, Modifier.focusRequester(done), Icons.Filled.Refresh,
                )
            }
        }
    }
}
