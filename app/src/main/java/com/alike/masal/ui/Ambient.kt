package com.alike.masal.ui

import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.Playback
import com.alike.masal.data.Channel
import com.alike.masal.data.PlexItem
import com.alike.masal.player.buildPlayer
import com.alike.masal.player.channelItem
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Video that starts after the focus has rested on an item for [delayMs]. */
class VideoSource(
    val key: String,
    val delayMs: Long,
    val volume: Float = 1f,
    val load: suspend () -> Pair<MediaItem, Long>?,
)

/** What the full-screen background shows: artwork (slow zoom), optionally replaced by video. */
data class Ambient(
    val key: String,
    val image: String?,
    val video: VideoSource? = null,
    val logo: String? = null,
    /** Plex's tint for the title (4 corners); the art then sits on the right like in Plex. */
    val colors: List<Int> = emptyList(),
)

/** How long focus must rest on an item before the background follows it. */
private const val AmbientSettle = 150L

/** Screens report their background here; MainActivity draws the one for the visible screen. */
val LocalAmbient = staticCompositionLocalOf<(Ambient?) -> Unit> { {} }

/** Reports [ambient] as this screen's background whenever it changes. */
@Composable
fun ReportAmbient(ambient: Ambient?) {
    val sink = LocalAmbient.current
    LaunchedEffect(ambient?.key, ambient?.image, ambient?.video?.key) {
        // While the user scrolls quickly through a row, only the item they rest on changes the background.
        kotlinx.coroutines.delay(AmbientSettle)
        sink(ambient)
    }
}

fun liveVideo(ch: Channel) = VideoSource("live:" + ch.key, 700) { channelItem(ch) to 0L }

fun liveAmbient(ch: Channel) = Ambient("live:" + ch.key, null, liveVideo(ch), ch.logo)

/**
 * Background for a Plex item: its artwork, and its trailer once the focus has rested on it
 * for a moment, when Plex has one (local trailer extras or Plex Pass online trailers).
 * Only the trailer plays, never the title itself, so nothing changes in Continue Watching.
 */
fun plexAmbient(item: PlexItem): Ambient {
    val image = Graph.plex.image(item.art ?: item.poster, 1920, 1080)
    val key = when (item.type) {
        "episode" -> item.grandparentRatingKey
        "season" -> item.parentRatingKey
        else -> item.ratingKey
    } ?: item.ratingKey
    val video = VideoSource("trailer:$key", 2_500, volume = 0.7f) {
        withContext(Dispatchers.IO) { Graph.plex.trailer(key) }?.partKey
            ?.let { MediaItem.fromUri(Graph.plex.streamUrl(it)) to 0L }
    }
    return Ambient("plex:" + item.ratingKey, image, video, colors = item.blur)
}

@Composable
fun AmbientBackground(ambient: Ambient?, modifier: Modifier = Modifier) {
    val image = ambient?.image
    // Plex's own tint when it has one, otherwise colours read from the picture.
    val corners = image?.let { ambient.colors.takeIf { c -> c.size == 4 } ?: rememberImageCorners(it) }
    Box(modifier.fillMaxSize().background(Bg)) {
        AnimatedContent(
            targetState = ambient,
            // The new picture fades in over the old one, which stays until it is covered:
            // no dip to black between two pictures.
            transitionSpec = {
                fadeIn(tween(450, easing = androidx.compose.animation.core.FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(150, delayMillis = 450))
            },
            contentKey = { it?.key + "|" + it?.image },
            label = "ambient",
        ) { a ->
            Box(Modifier.fillMaxSize()) {
                when {
                    a?.image != null -> ArtFrame(Modifier.graphicsLayer { clip = true }) { KenBurns(a.image) }
                    // Live channel: no logo placeholder, just a calm gradient until the picture fades in.
                    a?.logo != null || a?.video != null -> Box(Modifier.fillMaxSize().background(LiveGradient))
                }
            }
        }
        val video = ambient?.video
        if (video != null && image != null) {
            // Trailers and resume previews play in the picture's frame.
            ArtFrame(Modifier) { PreviewVideo(video, Modifier.fillMaxSize()) }
        } else if (video != null) {
            PreviewVideo(video, Modifier.fillMaxSize())
            FullScrims()
        }
        if (image != null) {
            TintAroundArt(corners)
            TextScrims()
        }
    }
}

/** Width of the picture frame; Plex shows its art on roughly the right 60 % of the screen. */
private const val ArtWidth = 0.62f

/** The top-right 16:9 frame the artwork (or its trailer) sits in. */
@Composable
private fun ArtFrame(modifier: Modifier, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        // Size first, then the caller's modifier, so masks and clips see the frame's own size.
        Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(ArtWidth).aspectRatio(16f / 9f).then(modifier)) { content() }
    }
}

/** Scrims for full-screen pictures: text on the left, rows at the bottom. */
@Composable
private fun FullScrims() {
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
        0f to Bg.copy(alpha = 0.92f), 0.4f to Bg.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Bg.copy(alpha = 0.45f), 0.18f to Color.Transparent, 0.5f to Bg.copy(alpha = 0.25f),
        0.82f to Bg.copy(alpha = 0.9f), 1f to Bg)))
}

/** Slightly darker towards the text on the left; kept light so the colours stay clear like in Plex. */
@Composable
private fun TextScrims() {
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
        0f to Color.Black.copy(alpha = 0.22f), 0.4f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.18f))))
}

/**
 * Plex look: the screen is filled with the picture's colours, the four corners blended
 * bilinearly (top edge top-left to top-right, bottom edge bottom-left to bottom-right), with a
 * soft hole where the picture sits. Next to the picture the fill takes the colour of the
 * picture's own edge, so the fade has no dark band. The colours glide to the next title's.
 */
@Composable
private fun TintAroundArt(corners: List<Int>?) {
    val target = corners ?: List(4) { 0xFF1E1C1A.toInt() }
    val c = List(4) { i ->
        androidx.compose.animation.animateColorAsState(Color(target[i]), tween(450), label = "tint$i").value
    }
    val (tl, tr, br, bl) = c
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawBehind {
                val all = androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size)
                drawRect(Brush.horizontalGradient(listOf(tl, tr)))
                drawIntoCanvas { canvas ->
                    // Bottom edge colours, faded in from top to bottom.
                    canvas.saveLayer(all, androidx.compose.ui.graphics.Paint())
                    drawRect(Brush.horizontalGradient(listOf(bl, br)))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    canvas.restore()

                    // The hole: fully open in the picture's top right, closing towards its left and bottom edges.
                    // Whole pixels, rounded like the frame's layout (fillMaxWidth, aspectRatio): a hole edge
                    // half a pixel off the picture's edge lets the dark background through as a thin line.
                    val w = (size.width * ArtWidth).roundToInt().toFloat()
                    val h = (w / (16f / 9f)).roundToInt().toFloat()
                    val left = size.width - w
                    val frame = androidx.compose.ui.geometry.Size(w, h)
                    canvas.saveLayer(
                        androidx.compose.ui.geometry.Rect(left, 0f, size.width, h),
                        androidx.compose.ui.graphics.Paint().apply { blendMode = androidx.compose.ui.graphics.BlendMode.DstOut },
                    )
                    translate(left, 0f) {
                        drawRect(Color.Black, size = frame)
                        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.Black, startX = 0f, endX = w),
                            size = frame, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                        drawRect(Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent, startY = 0f, endY = h),
                            size = frame, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    }
                    canvas.restore()
                }
            },
    )
}

/** Corner colours read from images that Plex has no tint for (app photos, posters only). */
private val cornerCache = java.util.concurrent.ConcurrentHashMap<String, List<Int>>()

@Composable
private fun rememberImageCorners(url: String): List<Int>? {
    val context = LocalContext.current
    var corners by remember(url) { mutableStateOf(cornerCache[url]) }
    LaunchedEffect(url) {
        if (corners != null) return@LaunchedEffect
        val request = coil.request.ImageRequest.Builder(context).data(url).size(24, 14).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? coil.request.SuccessResult ?: return@LaunchedEffect
        val bitmap = (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        corners = withContext(Dispatchers.Default) { imageCorners(bitmap) }.also { cornerCache[url] = it }
    }
    return corners
}

/**
 * Average colour of each image quarter (top-left, top-right, bottom-right, bottom-left, the
 * same order as Plex's UltraBlurColors), darkened and kept rich so white text reads on it.
 */
internal fun imageCorners(bitmap: android.graphics.Bitmap): List<Int> {
    val w = bitmap.width; val h = bitmap.height
    fun avg(x0: Int, x1: Int, y0: Int, y1: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = bitmap.getPixel(x, y)
            r += android.graphics.Color.red(c); g += android.graphics.Color.green(c); b += android.graphics.Color.blue(c); n++
        }
        if (n == 0) return 0xFF1E1C1A.toInt()
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(0.85f)
        hsv[2] = (hsv[2] * 0.55f).coerceIn(0.08f, 0.36f)
        return android.graphics.Color.HSVToColor(hsv)
    }
    val mx = w / 2; val my = h / 2
    return listOf(avg(0, mx, 0, my), avg(mx, w, 0, my), avg(mx, w, my, h), avg(0, mx, my, h))
}

/** Artwork that drifts and zooms very slowly inside its frame. */
@Composable
private fun KenBurns(url: String) {
    val t = rememberInfiniteTransition(label = "kenburns")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    NetImage(url, null, Modifier.fillMaxSize().graphicsLayer {
        val s = 1.02f + 0.04f * p
        scaleX = s; scaleY = s
        translationX = -size.width * 0.012f * p
        translationY = -size.height * 0.008f * p
    })
}

/**
 * Plays [source] once the focus rests on it, fading the picture in on the first
 * frame. Stops when the app leaves the foreground and before full-screen playback
 * (IPTV providers often allow only one connection). Back in the foreground (after the
 * player, another app or standby) the same preview does not start again by itself:
 * it waits until the focus moves, so returning to Home never bursts into sound.
 */
@Composable
fun PreviewVideo(source: VideoSource?, modifier: Modifier = Modifier, showStatus: Boolean = false) {
    val context = LocalContext.current
    val player = remember { buildPlayer(context) }
    var active by remember { mutableStateOf(true) }
    var state by remember { mutableStateOf("idle") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentSource by rememberUpdatedState(source)
    // The preview that was showing when the app went to the background; held until the focus moves.
    var held by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { active = false; held = currentSource?.key; player.stop() }
                Lifecycle.Event.ON_START -> active = true
                else -> Unit
            }
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { state = "playing" }
            override fun onPlayerError(error: PlaybackException) { state = "error" }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_ENDED) state = "idle"
            }
        }
        lifecycle.addObserver(observer)
        player.addListener(listener)
        val stop = { active = false; held = currentSource?.key; player.stop(); player.clearMediaItems() }
        Playback.previews.add(stop)
        onDispose {
            lifecycle.removeObserver(observer)
            Playback.previews.remove(stop)
            player.release()
        }
    }

    LaunchedEffect(source?.key, active) {
        player.stop()
        player.clearMediaItems()
        state = "idle"
        if (source?.key != held) held = null
        if (source == null || !active || source.key == held) return@LaunchedEffect
        delay(source.delayMs)
        state = "loading"
        val (item, start) = runCatching { source.load() }.getOrNull() ?: run { state = "none"; return@LaunchedEffect }
        withContext(Dispatchers.Main.immediate) { // ExoPlayer must only be used on the main thread
            player.volume = source.volume
            player.setMediaItem(item, start)
            player.prepare()
            player.play()
        }
    }

    val alpha by animateFloatAsState(if (state == "playing") 1f else 0f,
        tween(if (state == "playing") 900 else 200), label = "video")
    Box(modifier) {
        if (VideoPreviews.enabled) AndroidView(
            factory = { ctx -> TextureView(ctx).also { player.setVideoTextureView(it) } },
            modifier = Modifier.fillMaxSize().alpha(alpha),
        )
        if (showStatus) when (state) {
            "loading" -> Text("Tuning in", color = TextDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            "error" -> Text("Preview unavailable", color = TextDim, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** Turned off by the JVM UI tests, which cannot draw a TextureView. */
object VideoPreviews { @Volatile var enabled = true }

/** Small live preview box (TV tab): a calm gradient, no placeholder text, until the picture fades in. */
@Composable
fun LivePreview(channel: Channel?, modifier: Modifier = Modifier) {
    Box(modifier.background(LiveGradient)) {
        PreviewVideo(channel?.let { liveVideo(it) }, Modifier.fillMaxSize())
    }
}

/** Shown behind live channels before their picture starts. */
private val LiveGradient = Brush.linearGradient(
    listOf(Color(0xFF2A2420), Bg), start = androidx.compose.ui.geometry.Offset.Zero,
    end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
