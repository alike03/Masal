package com.alike.masal.player

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.alike.masal.Graph
import com.alike.masal.Playback
import com.alike.masal.R
import com.alike.masal.data.Channel
import com.alike.masal.data.PlexItem
import com.alike.masal.data.PlexStream
import com.alike.masal.ui.MasalTheme
import java.util.Locale
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private val handler = Handler(Looper.getMainLooper())
    private var live = true
    private var scrobbled = false
    private val state = LiveState()
    private val plex = PlexUiState()

    /** Channel that was playing before the current one ("last channel" key). */
    private var previous: Triple<String, List<Channel>, Int>? = null
    private var tunedKey: String? = null

    private val hideInfo = Runnable { state.infoVisible = false }
    private val tune = Runnable { tuneCurrent() }
    private val applyDigits = Runnable {
        val n = state.digits.toIntOrNull()
        state.digits = ""
        if (n != null && n in 1..state.channels.size) select(state.listName, state.channels, n - 1, immediate = true)
    }
    private val progressTick = object : Runnable {
        override fun run() {
            reportPlex(if (player.isPlaying) "playing" else "paused")
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = intent.getStringExtra("mode") != "plex"
        player = buildPlayer(this)

        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = false // only while playing, see updateScreenOn()
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
            isFocusableInTouchMode = true
            subtitleView?.apply {
                // Plex-like captions: white, bold, black outline, no box.
                val font = runCatching { ResourcesCompat.getFont(context, R.font.dm_sans) }.getOrNull()
                setStyle(CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, Typeface.create(font ?: Typeface.SANS_SERIF, Typeface.BOLD)))
                setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, 30f)
                setApplyEmbeddedFontSizes(false)
                setBottomPaddingFraction(0.07f)
            }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(ComposeView(this).apply {
            setContent {
                MasalTheme {
                    if (live) {
                        LiveOverlay(state) { name, list, i ->
                            state.listVisible = false
                            view.requestFocus()
                            select(name, list, i, immediate = true)
                        }
                    } else {
                        PlexOverlay(plex, PlexActions(
                            playPause = ::togglePlay,
                            seekBy = ::seekBy,
                            subtitles = ::openSubtitles,
                            audio = ::openAudio,
                            touch = ::touch,
                        ))
                    }
                }
            }
        }, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        view.requestFocus()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                updateScreenOn()
                // Live streams drop now and then (provider closes the connection, HLS falls behind): reconnect.
                if (live && retryLive()) return
                Toast.makeText(this@PlayerActivity, "Playback failed: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }

            override fun onPlaybackStateChanged(s: Int) {
                plex.buffering = s == Player.STATE_BUFFERING
                updateScreenOn()
                if (s == Player.STATE_READY) liveRetries = 0
                if (s == Player.STATE_ENDED && live) retryLive()
                if (s == Player.STATE_ENDED && !live) {
                    reportPlex("stopped")
                    finish()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateScreenOn()
                plex.playing = isPlaying
                if (!live && !isPlaying) handler.removeCallbacks(hideControls)
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!live) onPlexTracks(tracks)
            }
        })

        if (live) {
            if (Playback.channels.isEmpty()) { finish(); return }
            state.groups = Playback.groups
            state.epg = Playback.epg
            select(Playback.listName, Playback.channels, Playback.index, immediate = true)
        } else {
            playPlex()
        }
    }

    // ---- live TV ----

    /** Switches to a channel; while zapping quickly the stream is only opened once the user pauses. */
    private fun select(name: String, list: List<Channel>, i: Int, immediate: Boolean) {
        if (list.isEmpty()) return
        val idx = ((i % list.size) + list.size) % list.size
        state.current?.let { cur -> if (cur.key != list[idx].key) previous = Triple(state.listName, state.channels, state.index) }
        state.listName = name
        state.channels = list
        state.index = idx
        showInfo()
        handler.removeCallbacks(tune)
        if (immediate) tuneCurrent() else handler.postDelayed(tune, 450)
    }

    private fun tuneCurrent() {
        val ch = state.current ?: return
        // Compared by stream address: providers list some channel names twice (main and backup stream).
        if (ch.url == tunedUrl && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) return
        if (ch.url != tunedUrl) liveRetries = 0
        tunedKey = ch.key
        tunedUrl = ch.url
        Graph.settings.addRecent(ch.key)
        player.setMediaItem(channelItem(ch))
        player.prepare()
        player.playWhenReady = true
    }

    /** The screen stays on only while something plays or buffers to play; paused or failed, the TV may dim and sleep. */
    private fun updateScreenOn() {
        val s = player.playbackState
        view.keepScreenOn = player.playWhenReady && (s == Player.STATE_READY || s == Player.STATE_BUFFERING)
    }

    private var tunedUrl: String? = null
    private var liveRetries = 0
    private val retryTune = Runnable { tunedUrl = null; tuneCurrent() }

    /** Reconnects the live channel with growing pauses (2, 4, 6, 8, 10 s); false once given up. */
    private fun retryLive(): Boolean {
        if (liveRetries >= 5) return false
        liveRetries++
        handler.removeCallbacks(retryTune)
        handler.postDelayed(retryTune, 2_000L * liveRetries)
        return true
    }

    private fun showInfo() {
        state.infoVisible = true
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 5_000)
    }

    private val handledKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO,
        KeyEvent.KEYCODE_LAST_CHANNEL,
    ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)

    private fun zap(delta: Int) = select(state.listName, state.channels, state.index + delta, immediate = false)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        logKey(event)
        if (!live) return plexKey(event)

        if (state.listVisible) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    state.listVisible = false
                    view.requestFocus()
                }
                return true
            }
            return super.dispatchKeyEvent(event) // the list handles navigation
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            return if (event.keyCode in handledKeys) true else super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            // Up/Right: next channel, Down/Left: previous one.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_RIGHT -> zap(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_LEFT -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) {
                    state.infoVisible = false
                    state.listVisible = true
                }
            }
            KeyEvent.KEYCODE_INFO -> showInfo()
            KeyEvent.KEYCODE_LAST_CHANNEL -> previous?.let { (n, l, i) -> select(n, l, i, immediate = true) }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                if (state.digits.length < 4) state.digits += (event.keyCode - KeyEvent.KEYCODE_0).toString()
                handler.removeCallbacks(applyDigits)
                handler.postDelayed(applyDigits, 1_500)
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    // ---- Plex: controls ----

    private val hideControls = Runnable { if (plex.panel == null && player.isPlaying) setControls(false) }
    private val tickPosition = object : Runnable {
        override fun run() {
            plex.position = player.currentPosition
            plex.buffered = player.bufferedPosition
            plex.duration = player.duration.takeIf { it > 0 } ?: (plex.item?.duration ?: 0)
            handler.postDelayed(this, 500)
        }
    }
    private val commitSeek = Runnable {
        plex.seekTarget?.let { player.seekTo(it) }
        plex.seekTarget = null
    }

    /** Any remote activity keeps the controls up a little longer. */
    private fun touch() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 5_000)
    }

    private fun setControls(on: Boolean, focus: String = "play") {
        if (on) {
            plex.focusTarget = focus
            if (!plex.controls) plex.focusTick++
            plex.controls = true
            touch()
        } else {
            plex.controls = false
            handler.removeCallbacks(hideControls)
            view.requestFocus()
        }
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        touch()
    }

    private fun seekBy(delta: Long) {
        val dur = plex.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        plex.seekTarget = ((plex.seekTarget ?: player.currentPosition) + delta).coerceIn(0, dur)
        handler.removeCallbacks(commitSeek)
        handler.postDelayed(commitSeek, 600)
        touch()
    }

    private val mediaKeys = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND)

    private fun plexKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (!down) when {
                    plex.panel != null -> { plex.panel = null; setControls(true); plex.focusTick++ }
                    plex.controls -> setControls(false)
                    else -> finish()
                }
                return true
            }
            in mediaKeys -> {
                if (down && e.repeatCount == 0) when (e.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10_000)
                    else -> togglePlay()
                }
                if (down && plex.panel == null) setControls(true)
                return true
            }
        }
        if (plex.panel != null || plex.controls) {
            touch()
            return super.dispatchKeyEvent(e) // the overlay handles navigation
        }
        // Controls hidden: OK and Up/Down show them, Left/Right skip right away.
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) setControls(true); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    seekBy(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 30_000)
                    setControls(true, focus = "seek")
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---- Plex: playback ----

    private var current: PlexItem? = null
    private var tracksApplied = false
    private var pendingSubtitle: Long? = null

    private fun playPlex() {
        val start = Playback.plexItem
        if (start?.partKey == null) { finish(); return }
        plex.item = start
        // Full metadata lists the subtitle and audio streams; lists (Continue Watching) do not.
        thread {
            val full = runCatching { Graph.plex.metadata(start.ratingKey) }.getOrNull()
                ?.takeIf { it.partKey != null }?.copy(viewOffset = start.viewOffset) ?: start
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                load(full, start.viewOffset)
                handler.postDelayed(progressTick, 10_000)
                handler.post(tickPosition)
                setControls(true)
            }
        }
    }

    private fun load(item: PlexItem, position: Long) {
        current = item
        plex.item = item
        tracksApplied = false
        player.setMediaItem(mediaItem(item), position)
        player.prepare()
        player.playWhenReady = true
    }

    /** The file plus Plex's external subtitles (downloaded ones included) as side-loaded tracks. */
    private fun mediaItem(item: PlexItem): MediaItem {
        val subs = item.streams.filter { it.type == 3 && it.key != null }.mapNotNull { s ->
            val mime = when (s.codec.lowercase()) {
                "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt", "webvtt" -> MimeTypes.TEXT_VTT
                else -> return@mapNotNull null
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(Graph.plex.streamUrl(s.key!!)))
                .setMimeType(mime).setLanguage(s.languageCode.ifBlank { null }).setLabel(s.title)
                .setId("plex:${s.id}").build()
        }
        return MediaItem.Builder().setUri(Graph.plex.streamUrl(item.partKey!!)).setSubtitleConfigurations(subs).build()
    }

    private fun groups(type: Int) = player.currentTracks.groups.filter { it.type == type && it.isSupported }
    private fun plexId(g: Tracks.Group) = g.getTrackFormat(0).id?.substringAfter("plex:", "")?.toLongOrNull()
    private fun plexStreams(type: Int) = current?.streams.orEmpty().filter { it.type == type }

    /** Plex stream for an ExoPlayer track: side-loaded ones by id, embedded ones by their order in the file. */
    private fun streamFor(g: Tracks.Group, type: Int): PlexStream? {
        plexId(g)?.let { id -> return plexStreams(type).firstOrNull { it.id == id } }
        val embeddedGroups = groups(type).filter { plexId(it) == null }
        val embeddedStreams = plexStreams(type).filter { it.key == null }
        if (embeddedGroups.size != embeddedStreams.size) return null
        return embeddedStreams.getOrNull(embeddedGroups.indexOf(g))
    }

    private fun label(g: Tracks.Group, type: Int): String {
        streamFor(g, type)?.title?.takeIf { it.isNotBlank() }?.let { return it }
        val f = g.getTrackFormat(0)
        val lang = f.language?.let { Locale.forLanguageTag(it).displayLanguage }?.takeIf { it.isNotBlank() && it != "und" }
        return listOfNotNull(lang, f.label).joinToString(" ").ifBlank { "Track" }
    }

    private fun onPlexTracks(tracks: Tracks) {
        if (tracks.groups.isEmpty()) return
        if (!tracksApplied) {
            tracksApplied = true
            applyPlexChoice()
        }
        pendingSubtitle?.let { id ->
            groups(C.TRACK_TYPE_TEXT).firstOrNull { plexId(it) == id }?.let { selectText(it); pendingSubtitle = null }
        }
        plex.subtitleLabel = groups(C.TRACK_TYPE_TEXT).firstOrNull { it.isSelected }?.let { shortLabel(label(it, 3)) } ?: "Off"
        plex.audioLabel = groups(C.TRACK_TYPE_AUDIO).firstOrNull { it.isSelected }?.let { shortLabel(label(it, 2)) } ?: ""
    }

    private fun shortLabel(s: String) = s.substringBefore(" (").take(22)

    /** Starts with the subtitle and audio Plex has selected for this file (as other Plex apps would). */
    private fun applyPlexChoice() {
        val sub = plexStreams(3).firstOrNull { it.selected }
        val textGroup = sub?.let { s -> groups(C.TRACK_TYPE_TEXT).firstOrNull { streamFor(it, 3)?.id == s.id } }
        if (textGroup != null) selectText(textGroup) else selectText(null)
        val audio = plexStreams(2).firstOrNull { it.selected }
        audio?.let { a -> groups(C.TRACK_TYPE_AUDIO).firstOrNull { streamFor(it, 2)?.id == a.id } }?.let { selectTrack(it) }
    }

    private fun selectText(g: Tracks.Group?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (g == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT)
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
        player.trackSelectionParameters = b.build()
    }

    private fun selectTrack(g: Tracks.Group) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
    }

    /** Remembers the choice in Plex too. */
    private fun remember(param: String, streamId: Long) {
        val part = current?.partId ?: return
        thread { Graph.plex.selectStream(part, param, streamId) }
    }

    // ---- Plex: subtitle and audio panels ----

    private fun openSubtitles() {
        val text = groups(C.TRACK_TYPE_TEXT)
        val anySelected = text.any { it.isSelected }
        plex.panel = Panel("Subtitles", buildList {
            add(PanelOption("Off", selected = !anySelected) {
                selectText(null); remember("subtitleStreamID", 0); plex.panel = null; setControls(true)
            })
            text.forEach { g ->
                val s = streamFor(g, 3)
                add(PanelOption(label(g, 3), detail = listOfNotNull(s?.codec?.uppercase(), if (s?.forced == true) "Forced" else null,
                    if (s?.key != null) "External" else null).joinToString("  "), selected = g.isSelected) {
                    selectText(g); s?.let { remember("subtitleStreamID", it.id) }; plex.panel = null; setControls(true)
                })
            }
            add(PanelOption("Search for subtitles…", detail = "OpenSubtitles through your Plex server") { openLanguages() })
        })
    }

    private fun openAudio() {
        plex.panel = Panel("Audio", groups(C.TRACK_TYPE_AUDIO).map { g ->
            PanelOption(label(g, 2), selected = g.isSelected) {
                selectTrack(g); streamFor(g, 2)?.let { remember("audioStreamID", it.id) }; plex.panel = null; setControls(true)
            }
        }.ifEmpty { listOf(PanelOption("Only one audio track") { plex.panel = null }) })
    }

    private fun openLanguages() {
        val device = Locale.getDefault().language
        val langs = (listOf(device) + listOf("de", "en", "tr")).distinct()
        plex.panel = Panel("Search subtitles", langs.map { code ->
            PanelOption(Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(code)).replaceFirstChar { it.uppercase() }) {
                search(code)
            }
        }, note = "Language")
    }

    private fun search(lang: String) {
        val item = current ?: return
        plex.panel = Panel("Search subtitles", note = "Searching…")
        thread {
            val found = runCatching { Graph.plex.searchSubtitles(item.ratingKey, lang) }
            runOnUiThread {
                if (plex.panel?.title != "Search subtitles") return@runOnUiThread
                val list = found.getOrNull().orEmpty()
                plex.panel = when {
                    found.isFailure -> Panel("Search subtitles", note = "Search failed: ${found.exceptionOrNull()?.message}")
                    list.isEmpty() -> Panel("Search subtitles", note = "Nothing found")
                    else -> Panel("Search subtitles", list.map { r ->
                        PanelOption(r.title, detail = listOfNotNull(r.language, r.provider, if (r.hearingImpaired) "Hearing impaired" else null,
                            if (r.forced) "Forced" else null).joinToString("  ")) { download(r) }
                    }, note = "${list.size} found, best matches first")
                }
            }
        }
    }

    private fun download(r: com.alike.masal.data.SubtitleResult) {
        val item = current ?: return
        plex.panel = Panel("Search subtitles", note = "Downloading…")
        val before = item.streams.filter { it.type == 3 }.map { it.id }.toSet()
        thread {
            val result = runCatching {
                Graph.plex.downloadSubtitle(item.ratingKey, r)
                // Plex fetches the file in the background; wait until it shows up as a stream.
                var fresh: PlexItem? = null
                for (i in 0 until 20) {
                    Thread.sleep(1_500)
                    val m = Graph.plex.metadata(item.ratingKey)
                    if (m.streams.any { it.type == 3 && it.id !in before }) { fresh = m; break }
                }
                fresh ?: error("Plex did not finish the download")
            }
            runOnUiThread {
                val fresh = result.getOrNull()
                if (fresh == null) {
                    plex.panel = Panel("Search subtitles", note = "Download failed: ${result.exceptionOrNull()?.message}")
                    return@runOnUiThread
                }
                val added = fresh.streams.first { it.type == 3 && it.id !in before }
                pendingSubtitle = added.id
                remember("subtitleStreamID", added.id)
                plex.panel = null
                plex.message = "Subtitle added: ${added.title}"
                // Reload with the new side-loaded track; playback continues where it was.
                load(fresh.copy(viewOffset = player.currentPosition), player.currentPosition)
                tracksApplied = true
                setControls(false)
            }
        }
    }

    private fun reportPlex(state: String) {
        if (live) return
        val item = current ?: Playback.plexItem ?: return
        if (item.type == "clip") return // trailers have no watch state
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 } ?: item.duration
        val scrobble = !scrobbled && dur > 0 && pos > dur * 0.9
        if (scrobble) scrobbled = true
        thread {
            Graph.plex.timeline(item, state, pos, dur)
            if (scrobble) Graph.plex.scrobble(item.ratingKey)
        }
    }

    // ---- lifecycle ----

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(progressTick)
        handler.removeCallbacks(retryTune)
        reportPlex("stopped")
        player.pause()
        // Live TV makes no sense in the background; release the stream at once (providers often allow one
        // connection, and the Home preview reconnects right after Back) and close.
        if (live) { player.stop(); finish() }
    }

    override fun onRestart() {
        super.onRestart()
        if (!live) {
            player.play()
            handler.postDelayed(progressTick, 10_000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        player.release()
    }
}
