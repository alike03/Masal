package com.alike.masal.ui

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.Typography
import androidx.compose.runtime.remember
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import com.alike.masal.R
import coil.compose.AsyncImage
import com.alike.masal.data.Channel
import com.alike.masal.data.PlexItem
import com.alike.masal.data.Programme
import com.alike.masal.data.TvApp
import com.alike.masal.data.nowAndNext

val Accent = Color(0xFFE0A458)
val Bg = Color(0xFF121110)
val Panel = Color(0xFF1E1C1A)
val PanelFocus = Color(0xFF2C2824)
val Line = Color(0xFF36322E)
val TextMain = Color(0xFFF2EEE8)
val TextDim = Color(0xFFA8A29A)

/** Layout is designed for the Shield's 960x540 dp screen. */
val EdgePadding = 26.dp
/** Space above each screen's content (the menu is on the left, so only a small margin). */
val NavHeight = 22.dp

/** DM Sans: free (OFL) geometric sans close to the Circular face Plex uses (Plex's own font is licensed). */
@OptIn(ExperimentalTextApi::class)
val MasalFont = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.dm_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

/** Fraunces in its soft cut (OFL), only for the "Masal" wordmark; the interface stays DM Sans. */
@OptIn(ExperimentalTextApi::class)
val MasalWordmarkFont = FontFamily(
    Font(
        R.font.fraunces, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(600),
            FontVariation.Setting("SOFT", 100f),
            FontVariation.Setting("WONK", 0f),
            FontVariation.Setting("opsz", 144f),
        ),
    )
)

private fun fontTypography(): Typography {
    val d = Typography()
    fun TextStyle.f() = copy(fontFamily = MasalFont)
    return Typography(
        displayLarge = d.displayLarge.f(), displayMedium = d.displayMedium.f(), displaySmall = d.displaySmall.f(),
        headlineLarge = d.headlineLarge.f(), headlineMedium = d.headlineMedium.f(), headlineSmall = d.headlineSmall.f(),
        titleLarge = d.titleLarge.f(), titleMedium = d.titleMedium.f(), titleSmall = d.titleSmall.f(),
        bodyLarge = d.bodyLarge.f(), bodyMedium = d.bodyMedium.f(), bodySmall = d.bodySmall.f(),
        labelLarge = d.labelLarge.f(), labelMedium = d.labelMedium.f(), labelSmall = d.labelSmall.f(),
    )
}

@Composable
fun MasalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        typography = remember { fontTypography() },
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF1A1612),
            background = Bg,
            onBackground = TextMain,
            surface = Panel,
            onSurface = TextMain,
            onSurfaceVariant = TextDim,
            inverseSurface = TextMain,
            inverseOnSurface = Bg,
        ),
        content = { SoftScrolling(content) },
    )
}

val cardShape = RoundedCornerShape(6.dp)

@Composable
fun cardBorder() = CardDefaults.border(
    focusedBorder = Border(border = BorderStroke(2.dp, Accent), shape = cardShape),
)

@Composable
fun cardColors() = CardDefaults.colors(containerColor = Panel, focusedContainerColor = PanelFocus)

private val tokenRe = Regex("(X-Plex-Token=|password=)[^&]*")

/** AsyncImage that logs failures (with secrets removed) under the tag MasalImg. */
@Composable
fun NetImage(url: String?, description: String?, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    if (url == null) return
    val context = androidx.compose.ui.platform.LocalContext.current
    AsyncImage(
        model = remember(url) { coil.request.ImageRequest.Builder(context).data(url).crossfade(if (ImageFades.enabled) 300 else 0).build() },
        contentDescription = description,
        contentScale = scale,
        modifier = modifier,
        onError = { Log.w("MasalImg", "${url.replace(tokenRe, "$1[x]")}: ${it.result.throwable}") },
    )
}

@Composable
fun Shelf(title: String, content: LazyListScope.() -> Unit) {
    Column {
        Text(
            title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = EdgePadding, bottom = 2.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun FocusCard(
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        shape = CardDefaults.shape(cardShape),
        colors = cardColors(),
        border = cardBorder(),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        glow = CardDefaults.glow(focusedGlow = Glow(Accent.copy(alpha = 0.35f), 10.dp)),
    ) {
        Box(Modifier.fillMaxSize(), content = content)
    }
}

@Composable
fun ChannelCard(
    channel: Channel,
    now: Programme?,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit = {},
    cardModifier: Modifier = Modifier,
    width: Dp = 168.dp,
    /** Show the channel's category when there is no programme guide entry. */
    groupFallback: Boolean = true,
) {
    Column(Modifier.width(width)) {
        FocusCard(onClick, onFocus, cardModifier.fillMaxWidth().aspectRatio(16f / 9f), onLongClick) {
            Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
                if (channel.logo != null) {
                    NetImage(channel.logo, channel.name, Modifier.fillMaxSize(), ContentScale.Fit)
                } else {
                    Text(channel.name, color = TextMain, maxLines = 2, textAlign = TextAlign.Center, fontSize = 15.sp)
                }
            }
            if (favorite) Box(Modifier.padding(7.dp).size(8.dp).background(Accent, CircleShape))
            if (now != null) ProgrammeBar(now, Modifier.align(Alignment.BottomStart).fillMaxWidth())
        }
        Text(channel.name, color = TextMain, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        (now?.title ?: channel.group.takeIf { groupFallback })?.let {
            Text(it, color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Thin bar showing how far the current programme has progressed. */
@Composable
fun ProgrammeBar(p: Programme, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = Color(0x66000000)) {
    val now = System.currentTimeMillis()
    val f = if (p.stop > p.start) ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f) else 0f
    Box(modifier.height(height).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Accent))
    }
}

@Composable
fun PosterCard(
    item: PlexItem,
    imageUrl: String?,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    cardModifier: Modifier = Modifier,
    width: Dp = 104.dp,
    showLabels: Boolean = true,
) {
    Column(Modifier.width(width)) {
        FocusCard(onClick, onFocus, cardModifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            if (imageUrl == null) {
                Text(item.title, color = TextMain, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            NetImage(imageUrl, item.title, Modifier.fillMaxSize())
            if (item.type == "show" && item.leafCount > 0 && item.viewedLeafCount < item.leafCount) {
                val left = item.leafCount - item.viewedLeafCount
                Text(
                    "$left", color = Color(0xFF1A1612), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp)
                        .background(Accent, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (item.progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0xAA000000))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress.coerceIn(0f, 1f)).background(Accent))
                }
            }
        }
        if (showLabels) {
            Text(item.title, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
            if (item.subtitle.isNotBlank()) {
                Text(item.subtitle, color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun EpisodeCard(
    item: PlexItem,
    imageUrl: String?,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    cardModifier: Modifier = Modifier,
) {
    Column(Modifier.width(200.dp)) {
        FocusCard(onClick, onFocus, cardModifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            NetImage(imageUrl, item.episodeTitle, Modifier.fillMaxSize())
            if (item.progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0xAA000000))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(item.progress.coerceIn(0f, 1f)).background(Accent))
                }
            }
        }
        Text(stringResource(R.string.common_episode_title, item.index, item.episodeTitle.orEmpty()), color = TextMain, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(formatDuration(item.duration), color = TextDim, fontSize = 11.sp)
    }
}

@Composable
fun AppCard(
    app: TvApp,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    cardModifier: Modifier = Modifier,
    moving: Boolean = false,
    pinned: Boolean = false,
) {
    Column(Modifier.width(168.dp)) {
        FocusCard(onClick, onFocus, cardModifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            Image(
                bitmap = app.banner,
                contentDescription = app.label,
                contentScale = if (app.isBanner) ContentScale.Crop else ContentScale.Fit,
                modifier = if (app.isBanner) Modifier.fillMaxSize() else Modifier.size(56.dp).align(Alignment.Center),
            )
            if (moving) {
                Box(Modifier.fillMaxSize().border(3.dp, Accent, cardShape))
                Text("◀  ▶", color = OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)
                        .background(Accent, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp))
            }
            if (pinned) {
                Text("✓", color = OnAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                        .background(Accent, CircleShape).padding(top = 1.dp), textAlign = TextAlign.Center)
            }
        }
        Text(app.label, color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun LibraryCard(
    title: String,
    detail: String,
    artUrl: String?,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    cardModifier: Modifier = Modifier,
    width: Dp = 220.dp,
) {
    FocusCard(onClick, onFocus, cardModifier.width(width).aspectRatio(16f / 9f)) {
        NetImage(artUrl, title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x22000000), Color(0xDD000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Box(Modifier.width(22.dp).height(3.dp).background(Accent))
            Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            Text(detail, color = TextDim, fontSize = 11.sp)
        }
    }
}

/** Small outlined label, e.g. year, age rating, runtime. */
@Composable
fun Chip(text: String, highlight: Boolean = false) {
    Text(
        text,
        color = if (highlight) Color(0xFF1A1612) else TextMain,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .then(
                if (highlight) Modifier.background(Accent, RoundedCornerShape(3.dp))
                else Modifier.border(1.dp, Line, RoundedCornerShape(3.dp))
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MetaChips(item: PlexItem) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        item.year?.let { Chip("$it") }
        item.contentRating?.let { Chip(it) }
        if (item.type == "movie" || item.type == "episode") {
            if (item.duration > 0) Chip(formatDuration(item.duration))
        }
        if (item.type == "show" && item.childCount > 0) Chip(pluralStringResource(R.plurals.common_seasons_chip, item.childCount, item.childCount))
        item.rating?.let { Chip(String.format(java.util.Locale.US, "%.1f", it), highlight = true) }
    }
}

/** Plex style: "2h 6m", "45m" (in the app's language). */
@Composable
fun formatDuration(ms: Long): String {
    val min = (ms / 60_000).toInt()
    return if (min >= 60) stringResource(R.string.common_duration_hours, min / 60, min % 60)
    else stringResource(R.string.common_duration_minutes, min)
}

fun formatClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}

/**
 * Swaps hero text with a short fade and rise whenever [key] changes. With [cut] the old text
 * goes at once (used when the focus moved to another row, whose info slot is just opening).
 */
@Composable
fun <T> HeroSwap(target: T, key: (T) -> Any?, modifier: Modifier = Modifier, cut: Boolean = false, content: @Composable (T) -> Unit) {
    val cutNow by androidx.compose.runtime.rememberUpdatedState(cut)
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(380, delayMillis = 60)) + slideInVertically(tween(380)) { it / 12 }) togetherWith
                fadeOut(if (cutNow) androidx.compose.animation.core.snap() else tween(140))
        },
        contentKey = key,
        contentAlignment = Alignment.TopStart,
        label = "hero",
    ) { Box { content(it) } } // Box: lets the content keep its own width instead of the container's
}

val OnAccent = Color(0xFF1A1612)
private val pill = RoundedCornerShape(50)

/** Pill button: amber when primary, translucent outline otherwise; turns white with a warm glow when focused. */
@Composable
fun MasalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(pill),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Accent else Color.White.copy(alpha = 0.08f),
            contentColor = if (primary) OnAccent else TextMain,
            focusedContainerColor = TextMain,
            focusedContentColor = OnAccent,
            pressedContainerColor = Accent,
            pressedContentColor = OnAccent,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.08f, pressedScale = 0.97f),
        glow = ButtonDefaults.glow(focusedGlow = Glow(Accent.copy(alpha = 0.6f), 12.dp)),
        border = ButtonDefaults.border(
            border = if (primary) Border.None else Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)), shape = pill),
            focusedBorder = Border.None,
        ),
        contentPadding = PaddingValues(start = if (icon != null) 16.dp else 22.dp, end = 22.dp, top = 9.dp, bottom = 9.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun HeroLive(channel: Channel, programmes: List<Programme>?, modifier: Modifier = Modifier) {
    val (now, next) = programmes?.nowAndNext() ?: (null to null)
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    Column(modifier.width(400.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.common_live), highlight = true)
            Text(channel.group, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(channel.name, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        if (now != null) {
            Text(now.title, color = TextMain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("${fmt.format(java.util.Date(now.start))}–${fmt.format(java.util.Date(now.stop))}", color = TextDim, fontSize = 12.sp)
                ProgrammeBar(now, Modifier.padding(start = 10.dp).width(120.dp).clip(RoundedCornerShape(2.dp)), track = Line)
            }
            now.desc?.let {
                Text(it, color = TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp))
            }
        } else {
            Text(stringResource(R.string.common_no_guide), color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (next != null) {
            Text(stringResource(R.string.common_next, fmt.format(java.util.Date(next.start)), next.title), color = TextDim, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Keeps Compose's TV scrolling (the focused item is pulled towards a pivot) but glides there with
 * a critically damped spring instead of the platform's quick 125 ms tween. A spring keeps its speed
 * when the target moves, so holding a key no longer leaves the row lagging behind and still sliding.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SoftScrolling(content: @Composable () -> Unit) {
    val platform = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
    val soft = remember(platform) {
        object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
                androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) =
                platform.calculateScrollDistance(offset, size, containerSize)
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides soft, content = content)
}

/** Turned off by the JVM UI tests: Robolectric's frozen clock would leave images half faded in. */
object ImageFades { @Volatile var enabled = true }
