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

    /** Opened from Home: zooms back into the picture frame on exit and gives the stream back. */
    private var fromHome = false
    /** The channel opened with; only that one goes back to Home's preview. */
    private var openedKey: String? = null
    /** The player now belongs to Home's preview again: do not stop or release it here. */
    private var handedBack = false
    private lateinit var listener: Player.Listener

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

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.alike.masal.data.Language.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = intent.getStringExtra("mode") != "plex"
        fromHome = live && intent.getBooleanExtra("fromHome", false)
        val opened = Playback.channels.getOrNull(Playback.index)
        openedKey = opened?.key
        // The preview's running stream for this channel, when it was handed over: continue it.
        val adopted = Playback.handedOver?.takeIf { p ->
            live && opened != null && p.currentMediaItem?.localConfiguration?.uri?.toString() == opened.url
        }
        Playback.handedOver?.takeIf { it !== adopted }?.release()
        Playback.handedOver = null
        player = adopted ?: buildPlayer(this)

        // Live on a TextureView, which scales with the zoom animation; Plex keeps the SurfaceView (HDR).
        view = (if (live) layoutInflater.inflate(R.layout.live_player_view, null) as PlayerView else PlayerView(this)).apply {
            player = this@PlayerActivity.player
            keepScreenOn = false // only while playing, see updateScreenOn()
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        // Subtitle look from the player settings (colour, box or outline, size, position).
        subtitleFont = runCatching { ResourcesCompat.getFont(this, R.font.dm_sans) }.getOrNull()
        applySettings()
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
                            settings = { openSettings() },
                        ))
                    }
                }
            }
        }, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        view.requestFocus()

        listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                updateScreenOn()
                // Live streams drop now and then (provider closes the connection, HLS falls behind): reconnect.
                if (live && retryLive()) return
                Toast.makeText(this@PlayerActivity, getString(R.string.player_playback_failed, error.errorCodeName), Toast.LENGTH_LONG).show()
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
        }
        player.addListener(listener)

        if (live) {
            if (Playback.channels.isEmpty()) { finish(); return }
            state.groups = Playback.groups
            state.epg = Playback.epg
            if (adopted != null && opened != null) {
                // Already tuned: select() must not reopen the stream.
                tunedUrl = opened.url
                tunedKey = opened.key
                Graph.settings.addRecent(opened.key)
                updateScreenOn()
            }
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

        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) leaveLive()
            return true
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

    /**
     * Back in the live player. Opened from Home it zooms back into the picture frame; when the
     * channel is still the one opened, Home's preview continues the stream (no reconnect).
     */
    private fun leaveLive() {
        val ch = state.current
        // The stream moves to Home's frame right away, where it plays on under the shrinking window
        // (which keeps its last picture), so the end of the zoom out has no gap.
        if (fromHome && ch != null && ch.key == openedKey && player.playerError == null &&
            player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
            handler.removeCallbacksAndMessages(null)
            player.removeListener(listener)
            handedBack = true
            Playback.giveBack("live:" + ch.key, player)
        }
        finish()
        if (fromHome) {
            @Suppress("DEPRECATION") // overrideActivityTransition needs API 34
            overridePendingTransition(R.anim.masal_hold, R.anim.masal_zoom_out_to_frame)
        }
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
                    plex.panel?.back != null -> plex.panel = plex.panel?.back?.invoke()
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
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(plex.skipForward * 1000L)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-plex.skipBack * 1000L)
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
                    seekBy(if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -plex.skipBack * 1000L else plex.skipForward * 1000L)
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
        return listOfNotNull(lang, f.label).joinToString(" ").ifBlank { getString(R.string.player_track) }
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
        plex.subtitleLabel = groups(C.TRACK_TYPE_TEXT).firstOrNull { it.isSelected }?.let { shortLabel(label(it, 3)) } ?: getString(R.string.player_off)
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
        plex.panel = Panel(getString(R.string.player_subtitles), buildList {
            add(PanelOption(getString(R.string.player_off), selected = !anySelected) {
                selectText(null); remember("subtitleStreamID", 0); plex.panel = null; setControls(true)
            })
            text.forEach { g ->
                val s = streamFor(g, 3)
                add(PanelOption(label(g, 3), detail = listOfNotNull(s?.codec?.uppercase(), if (s?.forced == true) getString(R.string.player_forced) else null,
                    if (s?.key != null) getString(R.string.player_external) else null).joinToString("  "), selected = g.isSelected) {
                    selectText(g); s?.let { remember("subtitleStreamID", it.id) }; plex.panel = null; setControls(true)
                })
            }
            add(PanelOption(getString(R.string.player_search_for_subtitles), detail = getString(R.string.player_search_detail)) { openLanguages() })
        })
    }

    // ---- Plex: settings panel ----

    private var subtitleFont: Typeface? = null

    /** Applies the stored player settings: subtitle look now, skip lengths on buttons and keys. */
    private fun applySettings() {
        val s = Graph.settings
        view.subtitleView?.let { PlayerSettings.applySubtitles(it, s, subtitleFont) }
        plex.skipBack = s.skipBackSeconds
        plex.skipForward = s.skipForwardSeconds
    }

    private fun openSettings(focus: Int? = null) { plex.panel = settingsPanel(focus) }

    /** One line per setting with its value; OK opens its choices (stored for all titles). */
    private fun settingsPanel(focus: Int? = null): Panel {
        val s = Graph.settings
        val p = PlayerSettings
        fun pct(v: Int) = getString(R.string.player_percent, v)
        fun sec(v: Int) = getString(R.string.player_seconds, v)
        val lines = listOf(
            Triple(getString(R.string.player_subtitle_size), pct(s.subtitleSize)) { i: Int ->
                choose(i, getString(R.string.player_subtitle_size), p.sizes.map { pct(it) to it }, s.subtitleSize) { s.subtitleSize = it } },
            Triple(getString(R.string.player_subtitle_colour), getString(with(p) { colors.of(s.subtitleColor).label })) { i: Int ->
                choose(i, getString(R.string.player_subtitle_colour), p.colors.map { getString(it.label) to it.key }, s.subtitleColor) { s.subtitleColor = it } },
            Triple(getString(R.string.player_subtitle_background), getString(with(p) { backgrounds.of(s.subtitleBackground).label })) { i: Int ->
                choose(i, getString(R.string.player_subtitle_background), p.backgrounds.map { getString(it.label) to it.key }, s.subtitleBackground) { s.subtitleBackground = it } },
            Triple(getString(R.string.player_subtitle_position), getString(with(p) { positions.of(s.subtitlePosition).label })) { i: Int ->
                choose(i, getString(R.string.player_subtitle_position), p.positions.map { getString(it.label) to it.key }, s.subtitlePosition) { s.subtitlePosition = it } },
            Triple(getString(R.string.player_skip_back), sec(s.skipBackSeconds)) { i: Int ->
                choose(i, getString(R.string.player_skip_back), p.skipBack.map { sec(it) to it }, s.skipBackSeconds) { s.skipBackSeconds = it } },
            Triple(getString(R.string.player_skip_forward), sec(s.skipForwardSeconds)) { i: Int ->
                choose(i, getString(R.string.player_skip_forward), p.skipForward.map { sec(it) to it }, s.skipForwardSeconds) { s.skipForwardSeconds = it } },
        )
        return Panel(getString(R.string.player_settings), lines.mapIndexed { i, (label, value, open) -> PanelOption(label, value) { open(i) } },
            note = getString(R.string.player_settings_note), focus = focus)
    }

    /** The choices of one setting; picking applies it at once and returns to the settings list. */
    private fun <T> choose(line: Int, title: String, choices: List<Pair<String, T>>, current: T, set: (T) -> Unit) {
        plex.panel = Panel(title, choices.map { (label, value) ->
            PanelOption(label, selected = value == current) { set(value); applySettings(); openSettings(line) }
        }, back = { settingsPanel(line) })
    }

    private fun openAudio() {
        plex.panel = Panel(getString(R.string.player_audio), groups(C.TRACK_TYPE_AUDIO).map { g ->
            PanelOption(label(g, 2), selected = g.isSelected) {
                selectTrack(g); streamFor(g, 2)?.let { remember("audioStreamID", it.id) }; plex.panel = null; setControls(true)
            }
        }.ifEmpty { listOf(PanelOption(getString(R.string.player_only_one_audio)) { plex.panel = null }) })
    }

    private fun openLanguages() {
        val device = Locale.getDefault().language
        val langs = (listOf(device) + listOf("de", "en", "tr")).distinct()
        plex.panel = Panel(getString(R.string.player_search_subtitles), langs.map { code ->
            PanelOption(Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(code)).replaceFirstChar { it.uppercase() }) {
                search(code)
            }
        }, note = getString(R.string.player_language))
    }

    private fun search(lang: String) {
        val item = current ?: return
        plex.panel = Panel(getString(R.string.player_search_subtitles), note = getString(R.string.player_searching))
        thread {
            val found = runCatching { Graph.plex.searchSubtitles(item.ratingKey, lang) }
            runOnUiThread {
                if (plex.panel?.title != getString(R.string.player_search_subtitles)) return@runOnUiThread
                val list = found.getOrNull().orEmpty()
                plex.panel = when {
                    found.isFailure -> Panel(getString(R.string.player_search_subtitles), note = getString(R.string.player_search_failed, found.exceptionOrNull()?.message.toString()))
                    list.isEmpty() -> Panel(getString(R.string.player_search_subtitles), note = getString(R.string.player_nothing_found))
                    else -> Panel(getString(R.string.player_search_subtitles), list.map { r ->
                        PanelOption(r.title, detail = listOfNotNull(r.language, r.provider, if (r.hearingImpaired) getString(R.string.player_hearing_impaired) else null,
                            if (r.forced) getString(R.string.player_forced) else null).joinToString("  ")) { download(r) }
                    }, note = getString(R.string.player_found, list.size))
                }
            }
        }
    }

    private fun download(r: com.alike.masal.data.SubtitleResult) {
        val item = current ?: return
        plex.panel = Panel(getString(R.string.player_search_subtitles), note = getString(R.string.player_downloading))
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
                fresh ?: error(getString(R.string.player_download_unfinished))
            }
            runOnUiThread {
                val fresh = result.getOrNull()
                if (fresh == null) {
                    plex.panel = Panel(getString(R.string.player_search_subtitles), note = getString(R.string.player_download_failed, result.exceptionOrNull()?.message.toString()))
                    return@runOnUiThread
                }
                val added = fresh.streams.first { it.type == 3 && it.id !in before }
                pendingSubtitle = added.id
                remember("subtitleStreamID", added.id)
                plex.panel = null
                plex.message = getString(R.string.player_subtitle_added, added.title)
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
        // Given back to Home's preview, the stream keeps playing there: only let go of it.
        if (handedBack) { view.player = null; finish(); return }
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
        if (!handedBack) player.release()
    }
}
