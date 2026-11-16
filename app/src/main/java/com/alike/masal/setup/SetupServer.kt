package com.alike.masal.setup

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.alike.masal.data.IptvSource
import com.alike.masal.data.Settings
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.UUID

const val SETUP_PORT = 8765

/**
 * Temporary settings page on the local network. It only runs while the setup
 * screen is open and only answers under a random path that is shown in the QR code.
 */
class SetupServer(
    private val settings: Settings,
    private val key: String,
    private val onChanged: () -> Unit,
    /** The Masal logo as PNG for the browser tab; served under the same secret path. */
    private val favicon: ByteArray? = null,
) : NanoHTTPD(SETUP_PORT) {

    override fun serve(session: IHTTPSession): Response {
        val base = "/$key"
        if (session.uri == "$base/favicon.png" && favicon != null) {
            return newFixedLengthResponse(Response.Status.OK, "image/png", favicon.inputStream(), favicon.size.toLong())
        }
        if (session.uri.trimEnd('/') != base) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
        var notice: String? = null
        if (session.method == Method.POST) {
            session.parseBody(HashMap())
            val p = session.parameters.mapValues { it.value.firstOrNull().orEmpty().trim() }
            notice = handle(p)
            onChanged()
            val r = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "")
            r.addHeader("Location", "$base?saved=" + java.net.URLEncoder.encode(notice, "UTF-8"))
            return r
        }
        notice = session.parameters["saved"]?.firstOrNull()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page(base, notice))
    }

    /** "http://host:port" of a URL, without path, query or user info. */
    private fun hostOf(url: String): String = runCatching {
        val u = java.net.URI(url.trim())
        "${u.scheme}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
    }.getOrDefault("(address hidden)")

    private fun handle(p: Map<String, String>): String = when (p["action"]) {
        "plex" -> {
            if (p["url"].orEmpty().isNotBlank()) settings.plexUrl = p["url"]!!
            if (p["token"].orEmpty().isNotBlank()) settings.plexToken = p["token"]!!
            "Plex settings saved."
        }
        "home" -> {
            settings.homeLiveGroup = p["group"].orEmpty()
            settings.homeLiveTitle = p["title"].orEmpty()
            "Home row saved."
        }
        "add_m3u" -> {
            if (p["url"].isNullOrBlank()) "Playlist URL is missing."
            else {
                settings.sources = settings.sources + IptvSource(
                    id = UUID.randomUUID().toString().take(8),
                    name = p["name"].orEmpty().ifBlank { "Playlist" },
                    type = "m3u", url = p["url"]!!, epgUrl = p["epg"].orEmpty(),
                )
                "Playlist added."
            }
        }
        "add_xtream" -> {
            if (p["server"].isNullOrBlank() || p["user"].isNullOrBlank()) "Server and username are required."
            else {
                settings.sources = settings.sources + IptvSource(
                    id = UUID.randomUUID().toString().take(8),
                    name = p["name"].orEmpty().ifBlank { "IPTV" },
                    type = "xtream", server = p["server"]!!, user = p["user"]!!, pass = p["pass"].orEmpty(),
                    epgUrl = p["epg"].orEmpty(),
                )
                "Xtream login added."
            }
        }
        "delete" -> {
            settings.sources = settings.sources.filterNot { it.id == p["id"] }
            "Source removed."
        }
        else -> "Nothing changed."
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(base: String, notice: String?): String {
        val sources = settings.sources.joinToString("") { s ->
            // Only the host: playlist URLs and user names are half of the login, and anyone who sees the QR code can open this page.
            val detail = if (s.type == "xtream") "Xtream, ${esc(hostOf(s.server))}, user ${esc(s.user.take(2))}…" else "M3U, ${esc(hostOf(s.url))}"
            """<li><div><strong>${esc(s.name)}</strong><span>$detail</span></div>
               <form method="post" action="$base"><input type="hidden" name="action" value="delete">
               <input type="hidden" name="id" value="${esc(s.id)}"><button class="ghost">Remove</button></form></li>"""
        }.ifEmpty { "<li><span>No IPTV sources yet.</span></li>" }
        val plexState = if (settings.plexToken.isNotBlank()) "Token saved. Leave empty to keep it." else "Not set"
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Masal setup</title><link rel="icon" type="image/png" href="$base/favicon.png">
<style>
:root{--bg:#121110;--panel:#1e1c1a;--line:#36322e;--text:#f2eee8;--dim:#a8a29a;--accent:#e0a458}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:640px;margin:0 auto;padding:24px 16px 64px}h1{font-size:26px;margin:0 0 4px}h2{font-size:18px;margin:0 0 12px}
p.lead{color:var(--dim);margin:0 0 24px}section{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:18px;margin-bottom:18px}
label{display:block;font-size:14px;color:var(--dim);margin:10px 0 4px}input{width:100%;padding:11px 12px;border-radius:6px;border:1px solid var(--line);background:var(--bg);color:var(--text);font:inherit}
button{margin-top:14px;padding:11px 18px;border:0;border-radius:6px;background:var(--accent);color:#1a1612;font:inherit;font-weight:600}
button.ghost{margin:0;background:transparent;color:var(--dim);border:1px solid var(--line);padding:7px 12px}
ul{list-style:none;margin:0;padding:0}li{display:flex;gap:12px;align-items:center;justify-content:space-between;padding:10px 0;border-top:1px solid var(--line)}
li:first-child{border-top:0}li span{display:block;color:var(--dim);font-size:14px;word-break:break-all}
.notice{background:#2a2419;border:1px solid var(--accent);border-radius:6px;padding:10px 12px;margin-bottom:18px}
small{color:var(--dim)}
</style></head><body><main>
<h1>Masal setup</h1><p class="lead">Changes apply on the TV right away. This page closes when you leave the setup screen.</p>
${notice?.let { "<div class=\"notice\">${esc(it)}</div>" } ?: ""}
<section><h2>IPTV sources</h2><ul>$sources</ul></section>
<section><h2>Add Xtream login</h2><form method="post" action="$base"><input type="hidden" name="action" value="add_xtream">
<label>Name</label><input name="name" placeholder="My provider">
<label>Server URL</label><input name="server" placeholder="http://example-provider.tv:8080" autocapitalize="off" autocorrect="off">
<label>Username</label><input name="user" autocapitalize="off" autocorrect="off">
<label>Password</label><input name="pass" type="password">
<label>EPG URL (optional, default is the provider's xmltv.php)</label><input name="epg" autocapitalize="off">
<button>Add login</button></form></section>
<section><h2>Add M3U playlist</h2><form method="post" action="$base"><input type="hidden" name="action" value="add_m3u">
<label>Name</label><input name="name" placeholder="My playlist">
<label>Playlist URL</label><input name="url" placeholder="http://server:port/playlist.m3u" autocapitalize="off" autocorrect="off">
<label>EPG / XMLTV URL (optional)</label><input name="epg" placeholder="http://server:port/epg.xml" autocapitalize="off">
<button>Add playlist</button></form></section>
<section><h2>Home live TV row</h2><form method="post" action="$base"><input type="hidden" name="action" value="home">
<label>Channel category (as in the TV tab; empty hides the row)</label><input name="group" value="${esc(settings.homeLiveGroup)}" placeholder="News HD" autocapitalize="off">
<label>Row title</label><input name="title" value="${esc(settings.homeLiveTitle)}" placeholder="Live TV">
<button>Save</button></form></section>
<section><h2>Plex</h2><p><small>Easier: use the link code shown on the TV. Manual entry is here as a fallback.</small></p>
<form method="post" action="$base"><input type="hidden" name="action" value="plex">
<label>Server URL</label><input name="url" value="${esc(settings.plexUrl)}" placeholder="http://plex-server:32400" autocapitalize="off">
<label>Token</label><input name="token" type="password" placeholder="$plexState">
<button>Save Plex</button></form></section>
</main></body></html>"""
    }

    companion object {
        fun randomKey(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }

        fun localIp(): String = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull() ?: "0.0.0.0"

        fun qr(text: String, size: Int): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
