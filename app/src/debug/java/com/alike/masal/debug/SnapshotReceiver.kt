package com.alike.masal.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import com.alike.masal.Graph
import com.alike.masal.MasalViewModel
import com.alike.masal.data.Apps
import com.alike.masal.data.Epg
import com.alike.masal.data.IptvSource
import com.alike.masal.data.Backdrops
import com.alike.masal.data.PlexClient
import com.alike.masal.data.PlexItem
import okhttp3.HttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Debug builds only: writes a snapshot of the real library (Plex responses, artwork,
 * channel list, logos, a slice of the guide, app banners) to
 * Android/data/com.alike.masal/files/snapshot.zip, so the JVM UI tests can show real
 * content. Logins (Plex token, IPTV user and password) are replaced by [x] before
 * anything is written. Trigger: am broadcast -n com.alike.masal/.debug.SnapshotReceiver
 */
class SnapshotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        thread(name = "masal-snapshot") {
            runCatching { Snapshot(app).make() }.onFailure { Log.e(TAG, "snapshot failed", it) }
        }
    }

    companion object { const val TAG = "MasalSnap" }
}

/** Response key: path plus sorted query without the token (the test server computes the same). */
fun key(url: HttpUrl): String {
    val q = url.queryParameterNames.filter { it != "X-Plex-Token" }.sorted()
        .joinToString("&") { n -> n + "=" + url.queryParameter(n) }
    return url.encodedPath + if (q.isEmpty()) "" else "?$q"
}

private class Snapshot(private val context: Context) {
    private val settings = Graph.settings
    private val http = Graph.http
    private val baseDir = context.getExternalFilesDir(null)!!
    private val work = File(baseDir, "snapshot-work")
    private val secrets: List<String> = (listOf(settings.plexToken) + settings.sources.flatMap {
        listOf(it.user, it.pass, Uri.encode(it.user), Uri.encode(it.pass))
    }).filter { it.length >= 3 }.distinct().sortedByDescending { it.length }

    private val responses = JSONObject()
    private val images = JSONObject() // plex image path -> file name
    private val imageJobs = LinkedHashMap<String, Pair<Int, Int>>()
    private val logoJobs = LinkedHashSet<String>()

    private fun clean(text: String) = secrets.fold(text) { acc, s -> acc.replace(s, "[x]") }

    private fun get(url: String, plex: Boolean): String {
        val b = Request.Builder().url(url)
        if (plex) b.header("Accept", "application/json").header("X-Plex-Token", settings.plexToken)
            .header("X-Plex-Client-Identifier", settings.clientId).header("X-Plex-Product", "Masal")
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return r.body!!.string()
        }
    }

    private fun wantImage(path: String?, w: Int, h: Int) {
        if (!path.isNullOrBlank() && path !in imageJobs) imageJobs[path] = w to h
    }

    fun make() {
        val started = System.currentTimeMillis()
        work.deleteRecursively(); work.mkdirs()
        File(work, "images").mkdirs(); File(work, "logos").mkdirs(); File(work, "apps").mkdirs()
        File(baseDir, "snapshot.done").delete()

        if (settings.plexUrl.isNotBlank() && settings.plexToken.isNotBlank()) snapshotPlex()
        settings.sources.firstOrNull { it.type == "xtream" }?.let { snapshotIptv(it) }
        snapshotApps()
        snapshotBackdrops()

        File(work, "responses.json").writeText(responses.toString())
        File(work, "images.json").writeText(images.toString())
        File(work, "settings.json").writeText(JSONObject()
            .put("favorites", JSONArray(settings.favorites.toList()))
            .put("recent", JSONArray(settings.recentChannels))
            .put("homeLiveGroup", settings.homeLiveGroup)
            .put("homeLiveTitle", settings.homeLiveTitle)
            .toString())

        val zip = File(baseDir, "snapshot.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            work.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry(f.relativeTo(work).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        work.deleteRecursively()
        val summary = "ok ${zip.length() / 1024} KB, ${images.length()} images, ${responses.length()} responses, " +
            "${(System.currentTimeMillis() - started) / 1000} s"
        File(baseDir, "snapshot.done").writeText(summary)
        Log.i(SnapshotReceiver.TAG, summary)
    }

    /** Records every Plex response under [key], so the test server answers exactly these calls. */
    private val recorder = http.newBuilder().addInterceptor { chain ->
        val res = chain.proceed(chain.request())
        val url = chain.request().url
        if (res.isSuccessful && settings.plexUrl.contains(url.host)) {
            val body = res.peekBody(Long.MAX_VALUE).string()
            synchronized(responses) { responses.put(key(url), clean(body)) }
        }
        res
    }.build()
    private val client = PlexClient(recorder, settings)

    private fun wantItem(i: PlexItem) {
        wantImage(i.poster, 240, 360)
        wantImage(i.art, 1280, 720)
        if (i.type == "episode") wantImage(i.still, 400, 225)
        i.logo?.let { if (it !in logoJobs) logoJobs += it }
    }

    private fun details(i: PlexItem) {
        val type = i.type
        val showKey = if (type == "episode") i.grandparentRatingKey else if (type == "show") i.ratingKey else null
        runCatching { wantItem(client.metadata(i.ratingKey)) }
        runCatching { client.trailer(i.ratingKey) }
        if (showKey != null) {
            runCatching { wantItem(client.metadata(showKey)) }
            runCatching { client.showOnDeck(showKey) }
            runCatching { client.trailer(showKey) }
            val seasons = runCatching { client.children(showKey) }.getOrDefault(emptyList())
            seasons.forEach { wantItem(it) }
            seasons.take(3).forEach { s -> runCatching { client.children(s.ratingKey).forEach { wantItem(it) } } }
        }
    }

    private fun snapshotPlex() {
        val deck = runCatching { client.onDeck() }.getOrDefault(emptyList())
        val hubs = runCatching { client.homeHubs() }.getOrDefault(emptyList())
        val sections = runCatching { client.sections() }.getOrDefault(emptyList())
        (deck + hubs.flatMap { it.items }).forEach { wantItem(it) }
        deck.forEach { details(it) }
        hubs.flatMap { it.items.take(3) }.forEach { details(it) }
        deck.firstOrNull { it.type == "movie" }?.let { m ->
            for (lang in listOf("en", "de")) runCatching { client.searchSubtitles(m.ratingKey, lang) }
        }
        for (s in sections) {
            wantImage(s.art, 640, 360)
            val items = runCatching { client.sectionItems(s.key) }.getOrDefault(emptyList())
            items.take(60).forEach { wantItem(it) }
            items.forEach { wantImage(it.art, 640, 360) } // category tiles
            items.take(6).forEach { details(it) }
            val sHubs = runCatching { client.sectionHubs(s.key) }.getOrDefault(emptyList())
            sHubs.flatMap { it.items }.forEach { wantItem(it) }
            sHubs.flatMap { it.items.take(2) }.forEach { details(it) }
            runCatching { client.collections(s.key) }.getOrDefault(emptyList()).forEach { c ->
                wantImage(c.poster, 240, 360)
                runCatching { client.collectionItems(c.ratingKey).forEach { wantItem(it) } }
            }
            runCatching { client.genres(s.key) }.getOrDefault(emptyList()).take(6).forEach { (id, _) ->
                runCatching { client.genreItems(s.key, id).take(30).forEach { wantItem(it) } }
            }
        }
        // Download artwork in parallel through Plex's image transcoder.
        val pool = Executors.newFixedThreadPool(6)
        val digest = { p: String -> MessageDigest.getInstance("SHA-1").digest(p.toByteArray()).joinToString("") { "%02x".format(it) } }
        logoJobs.take(250).forEach { path ->
            pool.execute {
                runCatching {
                    val name = digest(path) + ".png"
                    http.newCall(Request.Builder().url(Graph.plex.logoImage(path)!!).build()).execute().use { r ->
                        if (r.isSuccessful) {
                            File(work, "images/$name").outputStream().use { r.body!!.byteStream().copyTo(it) }
                            synchronized(images) { images.put(path, name) }
                        }
                    }
                }
            }
        }
        imageJobs.entries.take(900).forEach { (path, size) ->
            pool.execute {
                runCatching {
                    val name = digest(path) + ".jpg"
                    val url = "${settings.plexUrl}/photo/:/transcode?width=${size.first}&height=${size.second}&minSize=1&upscale=1" +
                        "&url=${Uri.encode(path)}&X-Plex-Token=${settings.plexToken}"
                    http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                        if (r.isSuccessful) {
                            File(work, "images/$name").outputStream().use { r.body!!.byteStream().copyTo(it) }
                            synchronized(images) { images.put(path, name) }
                        }
                    }
                }
            }
        }
        pool.shutdown(); pool.awaitTermination(3, TimeUnit.MINUTES)
    }

    private fun snapshotIptv(src: IptvSource) {
        val base = src.server.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }
        fun api(action: String) = "$base/player_api.php?username=${Uri.encode(src.user)}&password=${Uri.encode(src.pass)}&action=$action"
        val cats = JSONArray(get(api("get_live_categories"), plex = false))
        val streams = JSONArray(get(api("get_live_streams"), plex = false))
        val catNames = HashMap<String, String>()
        for (i in 0 until cats.length()) cats.getJSONObject(i).let { catNames[it.optString("category_id")] = it.optString("category_name") }

        // Logos for the channels shown first (first categories) plus recent and favourite channels.
        val keep = (settings.recentChannels + settings.favorites).map { it.substringAfter('|') }.toSet()
        val catOrder = LinkedHashSet<String>()
        for (i in 0 until streams.length()) catOrder += streams.getJSONObject(i).optString("category_id")
        val homeGroup = MasalViewModel.normGroup(settings.homeLiveGroup)
        val homeCats = if (homeGroup.isEmpty()) emptySet() else catNames.filterValues { MasalViewModel.normGroup(it) == homeGroup }.keys
        val firstCats = catOrder.take(8).toSet() + homeCats
        val pool = Executors.newFixedThreadPool(8)
        var logoCount = 0
        for (i in 0 until streams.length()) {
            val o = streams.getJSONObject(i)
            val icon = o.optString("stream_icon")
            val name = o.optString("name").trim()
            val wanted = icon.startsWith("http") && (o.optString("category_id") in firstCats || name in keep) && logoCount < 700
            if (!wanted) { o.put("stream_icon", ""); continue }
            logoCount++
            val id = o.optString("stream_id")
            o.put("stream_icon", "MASAL_MOCK/logos/$id.png")
            pool.execute {
                runCatching {
                    http.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()
                        .newCall(Request.Builder().url(icon).build()).execute().use { r ->
                            if (r.isSuccessful) File(work, "logos/$id.png").outputStream().use { r.body!!.byteStream().copyTo(it) }
                        }
                }
            }
        }
        pool.shutdown(); pool.awaitTermination(2, TimeUnit.MINUTES)
        responses.put("xtream/get_live_categories", clean(cats.toString()))
        responses.put("xtream/get_live_streams", clean(streams.toString()))
        File(work, "source.json").writeText(JSONObject().put("id", src.id).put("name", src.name).toString())

        // A 13-hour slice of the cached guide for channels that have logos in the snapshot.
        val epgFile = File(context.cacheDir, "epg-${src.id}.xml")
        if (epgFile.exists()) {
            val wantedIds = (0 until streams.length()).map { streams.getJSONObject(it) }
                .filter { it.optString("stream_icon").isNotBlank() }
                .mapNotNull { it.optString("epg_channel_id").takeIf { id -> id.isNotBlank() && id != "null" } }.toSet()
            val now = System.currentTimeMillis()
            val guide = Epg.open(epgFile).use { Epg.parse(it, wantedIds, now - 3600_000L, now + 12 * 3600_000L) }
            val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
            fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            File(work, "epg.xml").bufferedWriter().use { w ->
                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
                guide.forEach { (ch, list) ->
                    list.forEach { p ->
                        w.write("<programme start=\"${fmt.format(p.start)}\" stop=\"${fmt.format(p.stop)}\" channel=\"${esc(ch)}\">")
                        w.write("<title>${esc(p.title)}</title>")
                        p.desc?.let { w.write("<desc>${esc(it)}</desc>") }
                        w.write("</programme>\n")
                    }
                }
                w.write("</tv>\n")
            }
        }
    }

    /** Bing's photos of the last days, for the backgrounds behind apps. */
    private fun snapshotBackdrops() = runCatching {
        File(work, "bing").mkdirs()
        val list = JSONArray()
        Backdrops.load(http).forEachIndexed { i, b ->
            http.newCall(Request.Builder().url(b.url).build()).execute().use { r ->
                if (r.isSuccessful) {
                    File(work, "bing/$i.jpg").outputStream().use { r.body!!.byteStream().copyTo(it) }
                    list.put(JSONObject().put("file", "$i.jpg").put("title", b.title).put("credit", b.credit))
                }
            }
        }
        File(work, "bing.json").writeText(list.toString())
    }.onFailure { Log.w(SnapshotReceiver.TAG, "backdrops: ${it.message}") }

    private fun snapshotApps() {
        val list = JSONArray()
        Apps.load(context).forEachIndexed { i, a ->
            val name = "app$i.png"
            runCatching {
                File(work, "apps/$name").outputStream().use { a.banner.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
                list.put(JSONObject().put("pkg", a.pkg).put("label", a.label).put("isBanner", a.isBanner).put("file", name))
            }
        }
        File(work, "apps.json").writeText(list.toString())
    }
}
