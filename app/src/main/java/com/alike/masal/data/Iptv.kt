package com.alike.masal.data

import android.net.Uri
import com.alike.masal.R
import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.JsonReader
import android.util.JsonToken
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class Channel(
    val key: String,
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val epgId: String?,
)

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String?)

fun List<Programme>.nowAndNext(now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
    val i = indexOfFirst { it.start <= now && it.stop > now }
    if (i < 0) return null to firstOrNull { it.start > now }
    return this[i] to getOrNull(i + 1)
}

object M3u {
    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(lines: Sequence<String>, sourceId: String): List<Channel> {
        val out = ArrayList<Channel>()
        var attrs: Map<String, String>? = null
        var name = ""
        var extGroup: String? = null
        val other by lazy { Language.text(R.string.tv_other) } // once, not per channel
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    attrs = attrRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val comma = line.indexOf(',', line.lastIndexOf('"').coerceAtLeast(0))
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    extGroup = null
                }
                line.startsWith("#EXTGRP:") -> extGroup = line.removePrefix("#EXTGRP:").trim()
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val a = attrs ?: continue
                    // Skip VOD entries that some providers mix into the live playlist.
                    if (line.contains("/movie/") || line.contains("/series/")) { attrs = null; continue }
                    val display = name.ifBlank { a["tvg-name"].orEmpty() }.ifBlank { "Channel ${out.size + 1}" }
                    out += Channel(
                        key = "$sourceId|$display",
                        name = display,
                        logo = a["tvg-logo"]?.takeIf { it.isNotBlank() },
                        group = a["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup ?: other,
                        url = line,
                        epgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                    )
                    attrs = null
                }
            }
        }
        return out
    }
}

object Xtream {
    private fun base(s: IptvSource) = s.server.trim().trimEnd('/').let {
        if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
    }

    private fun api(s: IptvSource, action: String) =
        "${base(s)}/player_api.php?username=${Uri.encode(s.user)}&password=${Uri.encode(s.pass)}&action=$action"

    fun epgUrl(s: IptvSource) =
        "${base(s)}/xmltv.php?username=${Uri.encode(s.user)}&password=${Uri.encode(s.pass)}"

    /**
     * The live channels. Read as a stream: a provider can list 30 000 streams, and holding the whole
     * response as one String plus a JSON tree costs tens of MB at every (re)load.
     */
    fun channels(http: OkHttpClient, s: IptvSource): List<Channel> {
        val catNames = HashMap<String, String>()
        http.readJson(api(s, "get_live_categories")) { r ->
            r.readArray {
                var id: String? = null; var name: String? = null
                r.readObject { k -> when (k) { "category_id" -> id = r.str(); "category_name" -> name = r.str(); else -> r.skipValue() } }
                id?.let { catNames[it] = name.orEmpty() }
            }
        }
        val other = Language.text(R.string.tv_other)
        val base = base(s)
        val user = Uri.encode(s.user)
        val pass = Uri.encode(s.pass)
        val out = ArrayList<Channel>()
        http.readJson(api(s, "get_live_streams")) { r ->
            r.readArray {
                var id = ""; var name = ""; var icon: String? = null; var cat: String? = null; var epg: String? = null
                r.readObject { k ->
                    when (k) {
                        "stream_id" -> id = r.str().orEmpty()
                        "name" -> name = r.str().orEmpty().trim()
                        "stream_icon" -> icon = r.str()
                        "category_id" -> cat = r.str()
                        "epg_channel_id" -> epg = r.str()
                        else -> r.skipValue()
                    }
                }
                out += Channel(
                    key = "${s.id}|$name",
                    name = name,
                    logo = icon?.takeIf { it.startsWith("http") },
                    group = cat?.let { catNames[it] } ?: other,
                    url = "$base/live/$user/$pass/$id.ts",
                    epgId = epg?.takeIf { it.isNotBlank() && it != "null" },
                )
            }
        }
        out.trimToSize()
        return out
    }
}

/** Reads a JSON response as a stream. */
internal fun <T> OkHttpClient.readJson(url: String, read: (JsonReader) -> T): T =
    newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
        if (!resp.isSuccessful) error("HTTP ${resp.code}")
        JsonReader(resp.body!!.charStream()).use { it.isLenient = true; read(it) }
    }

/** A value as text (numbers too, as providers send ids either way); null for JSON null, objects and arrays. */
internal fun JsonReader.str(): String? = when (peek()) {
    JsonToken.NULL -> { nextNull(); null }
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); null }
}

internal inline fun JsonReader.readArray(item: () -> Unit) {
    beginArray()
    while (hasNext()) item()
    endArray()
}

internal inline fun JsonReader.readObject(field: (String) -> Unit) {
    if (peek() != JsonToken.BEGIN_OBJECT) { skipValue(); return }
    beginObject()
    while (hasNext()) field(nextName())
    endObject()
}

object Epg {
    /**
     * XMLTV time "yyyyMMddHHmmss +hhmm" (offset optional, then UTC) in ms. Parsed by hand: a guide can
     * hold hundreds of thousands of programmes, and a new SimpleDateFormat per value is far too slow.
     */
    internal fun parseTime(v: String?): Long {
        val t = v?.trim() ?: return 0
        if (t.length < 14) return 0
        return runCatching {
            fun n(a: Int, b: Int) = t.substring(a, b).toInt()
            var ms = java.time.LocalDateTime.of(n(0, 4), n(4, 6), n(6, 8), n(8, 10), n(10, 12), n(12, 14))
                .toEpochSecond(java.time.ZoneOffset.UTC) * 1000
            val tz = t.substring(14).trim()
            if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                val offset = (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt()) * 60_000L
                ms -= if (tz[0] == '-') -offset else offset
            }
            ms
        }.getOrDefault(0)
    }

    /** Opens a file and transparently unpacks gzip (EPG files are often .xml.gz). */
    fun open(file: File): InputStream {
        val bis = BufferedInputStream(file.inputStream())
        bis.mark(2)
        val b1 = bis.read(); val b2 = bis.read()
        bis.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(bis) else bis
    }

    /** Streaming XMLTV parse that keeps only wanted channels within [from, to]. */
    fun parse(input: InputStream, wanted: Set<String>, from: Long, to: Long): Map<String, List<Programme>> {
        val out = HashMap<String, MutableList<Programme>>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Lenient: one stray "&" or "&nbsp;" in a description must not throw away the whole guide.
        runCatching { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        p.setInput(input, null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                val ch = p.getAttributeValue(null, "channel")
                // Channel first: most programmes belong to channels nobody looks at, skip them unparsed.
                val keep = ch != null && ch in wanted
                val start = if (keep) parseTime(p.getAttributeValue(null, "start")) else 0
                val stop = if (keep) parseTime(p.getAttributeValue(null, "stop")) else 0
                if (keep && stop > from && start < to) {
                    var title = ""; var desc: String? = null
                    var depth = 1
                    while (depth > 0) {
                        when (p.next()) {
                            XmlPullParser.START_TAG -> when (p.name) {
                                "title" -> if (title.isEmpty()) title = p.nextText() else skip(p)
                                "desc" -> if (desc == null) desc = p.nextText() else skip(p)
                                else -> skip(p)
                            }
                            XmlPullParser.END_TAG -> depth--
                            XmlPullParser.END_DOCUMENT -> depth = 0
                        }
                    }
                    out.getOrPut(ch) { ArrayList() } += Programme(start, stop, title, desc)
                } else {
                    skip(p)
                }
            }
            ev = p.next()
        }
        out.values.forEach { list -> list.sortBy { it.start } }
        return out
    }

    private fun skip(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}

fun OkHttpClient.getString(url: String): String {
    newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}")
        return r.body?.string() ?: ""
    }
}

fun OkHttpClient.download(url: String, target: File) {
    newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
        if (!r.isSuccessful) error("HTTP ${r.code}")
        val tmp = File(target.path + ".part")
        r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(target)
    }
}

const val USER_AGENT = "Masal/0.1 (Android TV)"

data class LoadResult<T>(val data: T, val errors: List<String>)

class IptvRepository(private val http: OkHttpClient, private val settings: Settings, private val cacheDir: File) {

    fun loadChannels(): LoadResult<List<Channel>> {
        val errors = ArrayList<String>()
        val all = ArrayList<Channel>()
        for (s in settings.sources) {
            runCatching {
                if (s.type == "xtream") Xtream.channels(http, s)
                else http.newCall(Request.Builder().url(s.url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    r.body!!.charStream().buffered().useLines { M3u.parse(it, s.id) }
                }
            }.onSuccess { all += it }
                .onFailure { errors += "${s.name}: ${it.message ?: it.javaClass.simpleName}" }
        }
        return LoadResult(all, errors)
    }

    fun loadEpg(channels: List<Channel>): LoadResult<Map<String, List<Programme>>> {
        val errors = ArrayList<String>()
        val wanted = channels.mapNotNull { it.epgId }.toSet()
        if (wanted.isEmpty()) return LoadResult(emptyMap(), errors)
        val now = System.currentTimeMillis()
        val result = HashMap<String, List<Programme>>()
        for (s in settings.sources) {
            val url = if (s.type == "xtream") s.epgUrl.ifBlank { Xtream.epgUrl(s) } else s.epgUrl
            if (url.isBlank()) continue
            runCatching {
                val file = File(cacheDir, "epg-${s.id}.xml")
                if (!file.exists() || now - file.lastModified() > 6 * 3600_000L) http.download(url, file)
                Epg.open(file).use { Epg.parse(it, wanted, now - 3600_000L, now + 24 * 3600_000L) }
            }.onSuccess { result.putAll(it) }
                .onFailure { errors += Language.text(R.string.common_guide_error, s.name, it.message ?: it.javaClass.simpleName) }
        }
        return LoadResult(result, errors)
    }
}
