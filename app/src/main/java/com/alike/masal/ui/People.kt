package com.alike.masal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alike.masal.Graph
import com.alike.masal.R
import com.alike.masal.data.CastMember
import com.alike.masal.data.PlexItem
import com.alike.masal.data.SeerrDetails
import com.alike.masal.data.SeerrPerson
import com.alike.masal.data.SeerrSeason
import com.alike.masal.data.SeerrStatus
import com.alike.masal.data.SeerrTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where the cast, person and Seerr pages lead (people.md, seerr.md). */
class PeopleActions(
    val onPerson: (CastMember) -> Unit,
    val onSeerrTitle: (SeerrTitle) -> Unit,
    /** A title in Plex by its ratingKey (film or show) and title. */
    val onPlexTitle: (String, String) -> Unit,
    /** Plays a film in Plex by its ratingKey and title; null where playing is not offered. */
    val onPlayPlex: ((String, String) -> Unit)? = null,
    /**
     * Opens a YouTube trailer (video id) in a YouTube app, used when it cannot play in Masal (no
     * trailer service, or the service failed); null where trailers are not offered (inside the player).
     */
    val onTrailer: ((String) -> Unit)? = null,
)

/** Actors (at most [actors]) and directors: what the player's paused strip shows. */
fun stripCast(people: List<CastMember>, actors: Int = 12): List<CastMember> =
    people.filter { it.job == null }.take(actors) + people.filter { it.job?.contains("Director") == true }

// ---- cast ----

@Composable
fun jobLabel(job: String): String {
    val labels = mapOf(
        "Director" to stringResource(R.string.people_job_director),
        "Screenplay" to stringResource(R.string.people_job_screenplay),
        "Writer" to stringResource(R.string.people_job_writer),
        "Creator" to stringResource(R.string.people_job_creator),
        "Producer" to stringResource(R.string.people_job_producer),
    )
    return job.split(", ").map { labels[it] ?: it }.distinct().joinToString(", ")
}

/** "as Rick" for an actor, the job for crew. */
@Composable
fun castLine(p: CastMember): String? = when {
    p.job != null -> jobLabel(p.job)
    p.role != null -> stringResource(R.string.people_as, p.role)
    else -> null
}

private fun initials(name: String) = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()

/** A round photo with the name and role below; OK opens the person. */
@Composable
fun PersonCard(p: CastMember, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 84.dp, onFocus: () -> Unit = {}) {
    Column(Modifier.width(size + 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(size).onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Panel, focusedContainerColor = PanelFocus,
                contentColor = TextDim, focusedContentColor = TextMain),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Accent), shape = CircleShape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Accent.copy(alpha = 0.35f), 10.dp)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(initials(p.name), fontSize = (size.value / 4).sp, fontWeight = FontWeight.Bold)
                NetImage(p.photo, p.name, Modifier.fillMaxSize().clip(CircleShape))
            }
        }
        Text(p.name, color = TextMain, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp))
        castLine(p)?.let {
            Text(it, color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** "Cast & Crew" row: actors, then directors, writers and producers. */
@Composable
fun CastRow(people: List<CastMember>, onPerson: (CastMember) -> Unit, itemModifier: (Int) -> Modifier = { Modifier }) {
    Column {
        RowTitle(stringResource(R.string.people_cast_crew))
        LazyRow(
            contentPadding = PaddingValues(horizontal = EdgePadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(people, key = { i, p -> "$i:${p.name}" }) { i, p -> PersonCard(p, { onPerson(p) }, itemModifier(i)) }
        }
    }
}

/**
 * A title's cast for the row and the player's strip: Seerr's (with TMDB ids, so person pages
 * get the full filmography) when Seerr is set up and the title has a TMDB id, else Plex's.
 */
suspend fun castFor(item: PlexItem, show: PlexItem? = null): List<CastMember> = withContext(Dispatchers.IO) {
    val base = show ?: item
    val plexCast = item.people.ifEmpty { show?.people.orEmpty() }
    val type = if (base.type == "show") "tv" else "movie"
    val id = base.tmdbId
    if (!Graph.seerr.configured || id == null || (base.type != "movie" && base.type != "show")) return@withContext plexCast
    val seerrCast = runCatching { (Graph.seerr.cachedDetails(type, id) ?: Graph.seerr.details(type, id)).cast }.getOrNull()
    // Seerr knows the people by TMDB id; where it has no cast, Plex's names and photos are kept.
    seerrCast?.takeIf { it.isNotEmpty() } ?: plexCast
}

// ---- status ----

/** The release day of [date] (yyyy-MM-dd) when it is still to come, else null. */
private fun futureRelease(date: String?): LocalDate? =
    runCatching { LocalDate.parse(date) }.getOrNull()?.takeIf { it.isAfter(LocalDate.now()) }

/** A short release date for a badge: "12. Dez." this year, "12. Dez. 2027" later. */
private fun shortRelease(day: LocalDate): String {
    val locale = java.util.Locale.getDefault()
    val skeleton = if (day.year == LocalDate.now().year) "dMMM" else "dMMMyyyy"
    return day.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale))
}

/**
 * A title's status (people.md): "Available", "Requested", or for an approved title its release
 * date while it is still to come, else "Soon". Nothing when it is neither in Plex nor requested.
 */
@Composable
fun StatusBadge(status: SeerrStatus, modifier: Modifier = Modifier, release: String? = null) {
    val text = when (status) {
        SeerrStatus.Available -> stringResource(R.string.seerr_status_available)
        SeerrStatus.Requested -> stringResource(R.string.seerr_status_requested)
        SeerrStatus.Processing -> futureRelease(release)?.let { shortRelease(it) } ?: stringResource(R.string.seerr_status_soon)
        SeerrStatus.None -> return
    }
    val available = status == SeerrStatus.Available
    Text(
        text, color = if (available) OnAccent else TextMain, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.background(if (available) Accent else Color(0xCC000000), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A Seerr title's poster with its status badge (people.md). */
@Composable
fun SeerrPosterCard(
    t: SeerrTitle, caption: String?, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 104.dp, onFocus: () -> Unit = {},
    /** Title and caption under the poster; Home's rows show posters only, the info block names the title. */
    showLabels: Boolean = true,
) {
    Column(Modifier.width(width)) {
        FocusCard(onClick, onFocus, modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            if (t.poster == null) {
                Text(t.title, color = TextMain, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            NetImage(t.poster, t.title, Modifier.fillMaxSize())
            StatusBadge(t.status, Modifier.align(Alignment.TopStart).padding(5.dp), t.date)
        }
        if (!showLabels) return@Column
        Text(t.title, color = TextMain, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        caption?.takeIf { it.isNotBlank() }?.let { Text(it, color = TextDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Year and the person's roles and jobs in a title, e.g. "2019  as Rick, Director". */
@Composable
private fun creditCaption(t: SeerrTitle): String {
    val jobs = t.jobs.map { j ->
        when {
            j.startsWith("as:") -> j.removePrefix("as:").ifBlank { null }?.let { stringResource(R.string.people_as, it) }
                ?: stringResource(R.string.people_acting)
            else -> jobLabel(j.removePrefix("job:"))
        }
    }.distinct()
    return listOfNotNull(t.year?.toString(), jobs.joinToString(", ").ifBlank { null }).joinToString("  ")
}

/** A title from Plex shown on a person page without Seerr: always "In Plex". */
private fun plexAsTitle(item: PlexItem) = SeerrTitle(
    tmdbId = item.tmdbId ?: 0, mediaType = if (item.type == "show") "tv" else "movie", title = item.title, year = item.year,
    poster = Graph.plex.image(item.poster, 240, 360), backdrop = Graph.plex.image(item.art, BackdropWidth, BackdropHeight),
    overview = item.summary, status = SeerrStatus.Available, ratingKey = item.ratingKey, date = item.released.orEmpty(),
)

private fun longDate(iso: String?): String? = runCatching {
    LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(java.util.Locale.getDefault()))
}.getOrNull()

// ---- person page ----

/**
 * A person's page (people.md): photo, facts and biography from Seerr, then the filmography with
 * each title's status. Without Seerr (or without a TMDB id) only the titles in Plex.
 */
@Composable
fun PersonScreen(person: CastMember, autoFocus: Boolean, profile: String?, actions: PeopleActions) {
    val seerr = Graph.seerr
    val tmdbId = person.tmdbId?.takeIf { seerr.configured }
    val details by produceState<SeerrPerson?>(null, tmdbId) {
        if (tmdbId != null) value = withContext(Dispatchers.IO) { runCatching { seerr.person(tmdbId) }.getOrNull() }
    }
    var titles by remember { mutableStateOf<List<SeerrTitle>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var fromSeerr by remember { mutableStateOf(false) }
    LaunchedEffect(person) {
        if (tmdbId != null) {
            withContext(Dispatchers.IO) { runCatching { seerr.cachedCredits(tmdbId) }.getOrNull() }?.let { titles = it; fromSeerr = true }
            // The statuses as they are now; the page updates in place.
            val fresh = withContext(Dispatchers.IO) { runCatching { seerr.credits(tmdbId) } }
            fresh.onSuccess { titles = it; fromSeerr = true }
            if (fresh.isSuccess || titles != null) return@LaunchedEffect
            failed = fresh.exceptionOrNull()?.message
        }
        val filter = person.plexFilter
        if (filter == null) { titles = titles ?: emptyList(); return@LaunchedEffect }
        withContext(Dispatchers.IO) { runCatching { Graph.plexFor(profile).personTitles(filter) } }
            .onSuccess { list -> titles = list.map { plexAsTitle(it) }; failed = null }
            .onFailure { failed = it.message }
    }
    var onlyPlex by rememberSaveable { mutableStateOf(false) }
    val shown = titles.orEmpty().filter { !onlyPlex || it.status == SeerrStatus.Available }
    fun key(t: SeerrTitle) = "t:${t.mediaType}:${t.tmdbId}:${t.ratingKey}"
    val focus = rememberRestorableFocus(autoFocus, titles != null)
    focus.defaultKey = shown.firstOrNull()?.let { key(it) } ?: if (fromSeerr) "filter:all" else null

    val backdrop = titles.orEmpty().let { l -> l.firstOrNull { it.status == SeerrStatus.Available && it.backdrop != null } ?: l.firstOrNull { it.backdrop != null } }
    ReportAmbient(Ambient("person:${person.tmdbId ?: person.name}", backdrop?.backdrop))

    var showBio by remember { mutableStateOf(false) }
    val name = details?.name ?: person.name
    val status = when {
        titles == null && failed == null -> stringResource(R.string.browse_loading)
        titles == null -> stringResource(R.string.browse_load_failed, failed.orEmpty())
        shown.isEmpty() -> stringResource(R.string.people_no_titles)
        else -> null
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = rememberLazyGridState(),
            columns = GridCells.Fixed(7),
            contentPadding = PaddingValues(start = EdgePadding, end = EdgePadding, top = 30.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                PersonHeader(person, details, name, onMore = { showBio = true }, moreModifier = focus.modifier("more"))
            }
            if (fromSeerr) item(key = "filter", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    FilterTab(stringResource(R.string.people_filter_all), !onlyPlex, { onlyPlex = false }, focus.modifier("filter:all"))
                    FilterTab(stringResource(R.string.people_filter_in_plex), onlyPlex, { onlyPlex = true }, focus.modifier("filter:plex"))
                }
            }
            if (status != null) item(key = "status", span = { GridItemSpan(maxLineSpan) }) {
                Text(status, color = TextDim, fontSize = 14.sp)
            }
            shown.forEach { t ->
                item(key = key(t)) {
                    SeerrPosterCard(t, creditCaption(t), onClick = {
                        val rk = t.ratingKey
                        if (t.status == SeerrStatus.Available && rk != null) actions.onPlexTitle(rk, t.title) else actions.onSeerrTitle(t)
                    }, modifier = focus.modifier(key(t)))
                }
            }
        }
        if (showBio) BiographyOverlay(name, details?.biography.orEmpty()) { showBio = false }
    }
}

@Composable
private fun PersonHeader(person: CastMember, details: SeerrPerson?, name: String, onMore: () -> Unit, moreModifier: Modifier) {
    Row(Modifier.padding(bottom = 6.dp)) {
        Box(Modifier.size(width = 120.dp, height = 180.dp).clip(RoundedCornerShape(6.dp)).background(Panel), contentAlignment = Alignment.Center) {
            Text(initials(name), color = TextDim, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            NetImage(details?.photo ?: person.photo, name, Modifier.fillMaxSize())
        }
        Column(Modifier.padding(start = 24.dp).widthIn(max = 600.dp)) {
            Text(name, color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val known = when (details?.knownFor) {
                null -> null
                "Acting" -> stringResource(R.string.people_known_acting)
                "Directing" -> stringResource(R.string.people_known_directing)
                "Writing" -> stringResource(R.string.people_known_writing)
                "Production" -> stringResource(R.string.people_known_production)
                else -> details.knownFor
            }
            val born = longDate(details?.birthday)?.let { d ->
                details?.placeOfBirth?.let { stringResource(R.string.people_born_in, d, it) } ?: stringResource(R.string.people_born, d)
            }
            val died = longDate(details?.deathday)?.let { stringResource(R.string.people_died, it) }
            listOfNotNull(known, born, died).joinToString("   ").takeIf { it.isNotBlank() }?.let {
                Text(it, color = TextDim, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            }
            val bio = details?.biography
            if (bio != null) {
                var cut by remember(bio) { mutableStateOf(false) }
                Text(bio, color = TextMain.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { cut = it.hasVisualOverflow }, modifier = Modifier.padding(top = 10.dp))
                if (cut) {
                    MasalButton(stringResource(R.string.people_more), onMore, moreModifier.padding(top = 10.dp), primary = false)
                }
            }
        }
    }
}

@Composable
private fun FilterTab(label: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onSelect,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) PanelFocus else Color(0x66000000),
            contentColor = if (selected) Accent else TextDim,
            focusedContainerColor = TextMain, focusedContentColor = Bg,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/** The whole biography; Up/Down scroll, OK or Back close it. */
@Composable
private fun BiographyOverlay(name: String, text: String, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val box = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(40); runCatching { box.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.96f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(640.dp).padding(vertical = 40.dp)
                .focusRequester(box)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key in setOf(Key.DirectionUp, Key.DirectionDown)
                    when (e.key) {
                        Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(160f) }; true }
                        Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-160f) }; true }
                        Key.DirectionCenter, Key.Enter -> { onClose(); true }
                        else -> true
                    }
                }
                .focusable()
                .verticalScroll(scroll),
        ) {
            Text(name, color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(text, color = TextMain.copy(alpha = 0.92f), fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

// ---- Seerr title page ----

/** How long the trailer takes to slide up out of view or back (seerr.md). */
private const val TrailerSlideMs = 450

/** A title outside Plex (or not yet fully in it), from Seerr, with "Request" (seerr.md). */
@Composable
fun SeerrTitleScreen(mediaType: String, tmdbId: Int, autoFocus: Boolean, profile: String?, actions: PeopleActions) {
    val seerr = Graph.seerr
    var details by remember { mutableStateOf<SeerrDetails?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(mediaType, tmdbId, reload) {
        if (details == null) details = withContext(Dispatchers.IO) { runCatching { seerr.cachedDetails(mediaType, tmdbId) }.getOrNull() }
        withContext(Dispatchers.IO) { runCatching { seerr.details(mediaType, tmdbId) } }
            .onSuccess { details = it; failed = null }
            .onFailure { if (details == null) failed = it.message }
    }
    val d = details
    val focus = rememberRestorableFocus(autoFocus, d != null)
    focus.defaultKey = "action"
    ReportAmbient(d?.let { Ambient("seerr:$mediaType:$tmdbId", it.title.backdrop) })

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // The trailer starts with the page in its own full-screen window (seerr.md): where trailers are
    // offered the window opens at once, black with the loading bar, until the trailer plays or it is
    // known there is none. Down or Back slide it up (paused) and the details in from below; Up from
    // the buttons brings it back.
    val offersTrailer = actions.onTrailer != null
    var trailerOn by remember { mutableStateOf(offersTrailer) }
    var trailerShown by remember { mutableStateOf(offersTrailer) }
    var trailer by remember { mutableStateOf<Trailer?>(null) }
    var trailerStarted by remember { mutableStateOf(false) }
    var trailerFailed by remember { mutableStateOf(false) }
    // 1: the trailer fills the screen, 0: the details; read in placement only.
    val slide = remember { Animatable(if (offersTrailer) 1f else 0f) }
    var snapNext by remember { mutableStateOf(false) }
    val windowUp by remember { derivedStateOf { slide.value > 0f } }
    LaunchedEffect(trailerShown) {
        val from = slide.value
        val to = if (trailerShown) 1f else 0f
        if (snapNext) { snapNext = false; slide.snapTo(to) }
        else slide.animateTo(to, tween(TrailerSlideMs, easing = FastOutSlowInEasing))
        if (!trailerShown && from > 0f) focus.focusDefault()
    }
    fun showVideo() { trailerShown = true; trailer?.play() }
    fun showDetails() { trailer?.pause(); trailerShown = false }
    /** Ends the trailer; [snap]: without the slide (no trailer found, nothing to see yet). */
    fun stopTrailer(snap: Boolean = false) {
        trailer?.destroy(); trailer = null
        trailerOn = false
        if (trailerShown) { snapNext = snap; trailerShown = false }
    }
    DisposableEffect(Unit) { onDispose { trailer?.destroy() } }
    /** Plays [url] in Masal's own player ([hls]: a YouTube stream from the trailer service). */
    fun playTrailer(url: String, hls: Boolean, onFail: () -> Unit) {
        trailerOn = true
        showVideo()
        if (VideoPreviews.enabled) trailer = VideoTrailer(context, url, hls) { st ->
            when (st) {
                TrailerState.Playing -> trailerStarted = true
                TrailerState.Ended -> stopTrailer()
                TrailerState.Failed -> { trailer?.destroy(); trailer = null; onFail() }
                else -> {}
            }
        }
    }
    /** No trailer to play here: the details, with "Open in YouTube" when Seerr names one. */
    fun noTrailer(youTube: String?, failed: Boolean) {
        stopTrailer(snap = !trailerStarted)
        if (youTube != null) {
            trailerFailed = true
            if (failed) message = context.getString(R.string.seerr_trailer_failed)
        }
    }
    /** The YouTube trailer Seerr names, through the household's trailer service (seerr.md). */
    fun startYouTube(key: String) {
        if (!Graph.trailers.configured) { noTrailer(key, failed = false); return }
        scope.launch {
            val url = withContext(Dispatchers.IO) { Graph.trailers.youTube(key) }
            if (url == null) noTrailer(key, failed = true) else playTrailer(url, hls = true) { noTrailer(key, failed = true) }
        }
    }
    /** Plex's online trailer first, else the YouTube trailer; both in Masal's own player. */
    fun startTrailer(plexUrl: String?, youTube: String?) {
        com.alike.masal.Playback.stopPreviews()
        if (plexUrl == null) { youTube?.let { startYouTube(it) }; return }
        playTrailer(plexUrl, hls = false) { if (youTube != null) startYouTube(youTube) else stopTrailer() }
    }
    fun send(seasons: List<Int>) {
        if (sending) return
        sending = true; message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { seerr.request(mediaType, tmdbId, seasons, seerr.userFor(profile)) }
            }
            sending = false; confirming = false; picking = false
            message = r.fold({ context.getString(R.string.seerr_requested) }, { context.getString(R.string.seerr_request_failed, it.message.orEmpty()) })
            if (r.isSuccess) {
                // Shown at once; Seerr's own state follows with the reload.
                details = details?.let { old ->
                    old.copy(title = old.title.copy(status = if (old.title.status == SeerrStatus.Available) SeerrStatus.Available else SeerrStatus.Requested),
                        seasons = old.seasons.map { if (it.number in seasons) it.copy(status = SeerrStatus.Requested) else it })
                }
                reload++
            }
            delay(60); focus.focusDefault()
        }
    }

    // Starts once per page (not inside the player, where actions.onTrailer is null): Plex's online
    // trailer when Plex has one for the TMDB id, else the YouTube trailer Seerr names.
    var autoStarted by remember { mutableStateOf(false) }
    LaunchedEffect(d != null, failed != null) {
        if (autoStarted || !offersTrailer) return@LaunchedEffect
        if (d == null) { if (failed != null) stopTrailer(snap = true); return@LaunchedEffect }
        autoStarted = true
        val plexUrl = if (Graph.plex.configured) withContext(Dispatchers.IO) { Graph.plex.onlineTrailer(tmdbId, mediaType, seerr.language()) } else null
        if (plexUrl != null || d.trailer != null) startTrailer(plexUrl, d.trailer) else stopTrailer(snap = true)
    }

    // The details slide in from below as the trailer slides up.
    val belowTrailer = Modifier.layout { m, c ->
        val p = m.measure(c)
        layout(p.width, p.height) { p.place(0, (slide.value * p.height).roundToInt()) }
    }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (!trailerOn) return@onPreviewKeyEvent false
        when (e.key) {
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { if (e.type == KeyEventType.KeyUp) trailer?.toggle(); true }
            else -> false
        }
    }) {
        when {
            d == null && failed == null -> Text(stringResource(R.string.browse_loading), color = TextDim, modifier = belowTrailer.align(Alignment.Center))
            d == null -> Text(stringResource(R.string.browse_load_failed, failed.orEmpty()), color = Accent, modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(
                state = rememberLazyListState(),
                modifier = belowTrailer.fillMaxSize(),
                contentPadding = PaddingValues(top = 40.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "header") {
                    // Up from the buttons brings the trailer back.
                    Row(Modifier.fillMaxWidth().padding(horizontal = EdgePadding).onPreviewKeyEvent { e ->
                        if (!trailerOn || e.key != Key.DirectionUp || picking || confirming) return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown) showVideo()
                        true
                    }) {
                        Box(Modifier.size(width = 130.dp, height = 195.dp).clip(RoundedCornerShape(6.dp)).background(Panel)) {
                            NetImage(d.title.poster, d.title.title, Modifier.fillMaxSize())
                        }
                        Column(Modifier.padding(start = 24.dp).width(540.dp)) {
                            Text(d.title.title, color = TextMain, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val length = if (mediaType == "tv") d.seasons.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.seerr_seasons_count, it, it) }
                                else d.runtime?.let { formatDuration(it * 60_000L) }
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                // Still to come: the release day instead of the year.
                                val released = futureRelease(d.title.date)?.let { stringResource(R.string.seerr_release_on, longDate(it.toString()).orEmpty()) }
                                    ?: d.title.year?.toString()
                                Text(listOfNotNull(released, length, d.genres.take(3).joinToString(", ").ifBlank { null }).joinToString("   "),
                                    color = TextDim, fontSize = 13.sp, maxLines = 1)
                                StatusBadge(d.title.status, Modifier.padding(start = 10.dp))
                            }
                            d.title.overview?.let {
                                Text(it, color = TextMain.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 5,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                            }
                            RequestActions(d, mediaType, confirming, sending, focus.modifier("action"),
                                onRequest = { if (mediaType == "tv") picking = true else confirming = true },
                                onConfirm = { send(emptyList()) },
                                onCancel = { confirming = false },
                                onOpen = { d.title.ratingKey?.let { actions.onPlexTitle(it, d.title.title) } },
                                onPlay = actions.onPlayPlex?.takeIf { mediaType == "movie" }?.let { play -> { d.title.ratingKey?.let { play(it, d.title.title) } } },
                                // Only when the YouTube trailer cannot play here: open it in a YouTube app instead.
                                trailerLabel = if (trailerFailed && d.trailer != null && actions.onTrailer != null)
                                    stringResource(R.string.seerr_trailer_external) else null,
                                onTrailer = { d.trailer?.let { actions.onTrailer?.invoke(it) } })
                            message?.let { Text(it, color = Accent, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp)) }
                        }
                    }
                }
                if (d.cast.isNotEmpty()) item(key = "cast") {
                    CastRow(d.cast, actions.onPerson) { i -> focus.modifier("cast:$i") }
                }
                item(key = "end") { Spacer(Modifier.height(26.dp)) }
            }
        }
        if (picking && d != null) SeasonPicker(d.seasons, sending, onCancel = { picking = false }, onRequest = { send(it) })
        if (trailerShown || windowUp) TrailerWindow(trailer, loading = !trailerStarted, slide = { slide.value }) { showDetails() }
    }
}

/** Seasons that can still be requested: neither in Plex nor requested. */
private fun requestable(d: SeerrDetails, mediaType: String) =
    if (mediaType == "tv") d.seasons.any { it.status == SeerrStatus.None } else d.title.status == SeerrStatus.None

@Composable
private fun RequestActions(
    d: SeerrDetails, mediaType: String, confirming: Boolean, sending: Boolean, firstModifier: Modifier,
    onRequest: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit, onOpen: () -> Unit,
    /** Plays a film in Plex; null where playing is not offered (series, inside the player). */
    onPlay: (() -> Unit)? = null,
    /** The trailer button's text ("Trailer", "Open in YouTube"); null without a trailer. */
    trailerLabel: String? = null,
    onTrailer: () -> Unit = {},
) {
    val canRequest = requestable(d, mediaType)
    val canOpen = d.title.status == SeerrStatus.Available && d.title.ratingKey != null
    if (confirming) {
        Text(stringResource(R.string.seerr_request_confirm, d.title.title), color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MasalButton(stringResource(if (sending) R.string.seerr_sending else R.string.seerr_request), onConfirm, firstModifier)
            MasalButton(stringResource(R.string.seerr_cancel), onCancel, primary = false)
        }
        return
    }
    val pending = d.title.status == SeerrStatus.Requested || d.title.status == SeerrStatus.Processing
    if (pending && !canRequest) {
        val requested = d.requestedAt?.let { longDate(it) }
        val text = if (d.title.status == SeerrStatus.Processing) {
            // The release day is in the line under the title.
            val state = stringResource(R.string.seerr_status_processing)
            requested?.let { stringResource(R.string.seerr_requested_on, state, it) } ?: state
        } else requested?.let { stringResource(R.string.seerr_requested_on_only, it) } ?: stringResource(R.string.seerr_status_requested)
        Text(text,
            color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        var first = true
        fun mod(): Modifier = if (first) { first = false; firstModifier } else Modifier
        if (canOpen && onPlay != null) MasalButton(stringResource(R.string.browse_play), onPlay, mod())
        if (canOpen) MasalButton(stringResource(R.string.seerr_open), onOpen, mod(), primary = onPlay == null)
        if (canRequest) MasalButton(stringResource(R.string.seerr_request), onRequest, mod(), primary = !canOpen)
        if (trailerLabel != null) MasalButton(trailerLabel, onTrailer, mod(), primary = false)
        // Nothing to press: an empty focus target keeps the page reachable for the cast row.
        if (!canOpen && !canRequest && trailerLabel == null) Box(mod().size(1.dp).focusable())
    }
}

/** Seasons to request: those in Plex or requested are marked and cannot be picked. */
@Composable
private fun SeasonPicker(seasons: List<SeerrSeason>, sending: Boolean, onCancel: () -> Unit, onRequest: (List<Int>) -> Unit) {
    BackHandler(onBack = onCancel)
    val picked = remember { mutableStateListOf<Int>().apply { addAll(seasons.filter { it.status == SeerrStatus.None }.map { it.number }) } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Bg.copy(alpha = 0.94f)), contentAlignment = Alignment.CenterStart) {
        Column(Modifier.padding(start = EdgePadding + 40.dp).width(520.dp)) {
            Text(stringResource(R.string.seerr_seasons_title), color = TextMain, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.seerr_seasons_note), color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            LazyColumn(Modifier.height(300.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(seasons, key = { _, s -> s.number }) { i, s ->
                    val open = s.status == SeerrStatus.None
                    val label = s.name.ifBlank { stringResource(R.string.seerr_season, s.number) }
                    val detail = pluralStringResource(R.plurals.seerr_episodes, s.episodes, s.episodes)
                    if (open) {
                        Surface(
                            onClick = { if (s.number in picked) picked.remove(s.number) else picked.add(s.number) },
                            modifier = Modifier.fillMaxWidth().then(if (i == seasons.indexOfFirst { it.status == SeerrStatus.None }) Modifier.focusRequester(first) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Panel, contentColor = TextMain,
                                focusedContainerColor = TextMain, focusedContentColor = OnAccent),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        ) { SeasonLine(label, detail, s.number in picked, null) }
                    } else {
                        Box(Modifier.fillMaxWidth().background(Panel.copy(alpha = 0.5f), RoundedCornerShape(10.dp))) {
                            SeasonLine(label, detail, false, s.status)
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val label = if (sending) stringResource(R.string.seerr_sending) else pluralStringResource(R.plurals.seerr_request_seasons, picked.size, picked.size)
                MasalButton(label, { if (picked.isNotEmpty()) onRequest(picked.sorted()) },
                    if (seasons.none { it.status == SeerrStatus.None }) Modifier.focusRequester(first) else Modifier)
                MasalButton(stringResource(R.string.seerr_cancel), onCancel, primary = false)
            }
        }
    }
}

@Composable
private fun SeasonLine(label: String, detail: String, checked: Boolean, status: SeerrStatus?) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(30.dp)) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.settings_check_on), modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).alpha(if (status != null) 0.6f else 1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = if (status != null) TextMain else Color.Unspecified)
            Text(detail, fontSize = 12.sp, maxLines = 1, color = if (status != null) TextDim else Color.Unspecified)
        }
        if (status != null) StatusBadge(status)
    }
}

/** Shown in the player before a title in Plex opens: the current one stops (its place is saved). */
@Composable
fun LeavePlayerDialog(title: String, onLeave: () -> Unit, onStay: () -> Unit) {
    BackHandler(onBack = onStay)
    val stay = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { stay.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).background(Panel, RoundedCornerShape(12.dp)).padding(24.dp)) {
            Text(stringResource(R.string.people_leave_player), color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.people_leave_player_note, title), color = TextDim, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MasalButton(stringResource(R.string.people_leave_yes), onLeave)
                MasalButton(stringResource(R.string.people_leave_no), onStay, Modifier.focusRequester(stay), primary = false)
            }
        }
    }
}
