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
    /** Files the page uses (logo, favicon, fonts) by name: MIME type and bytes. Served under the same secret path. */
    private val assets: Map<String, Pair<String, ByteArray>> = emptyMap(),
) : NanoHTTPD(SETUP_PORT) {

    override fun serve(session: IHTTPSession): Response {
        val base = "/$key"
        assets[session.uri.removePrefix("$base/")]?.takeIf { session.uri.startsWith("$base/") }?.let { (mime, bytes) ->
            return newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
                .apply { addHeader("Cache-Control", "max-age=3600") }
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
            val xtream = s.type == "xtream"
            val detail = if (xtream) "${esc(hostOf(s.server))} &middot; user ${esc(s.user.take(2))}&hellip;" else esc(hostOf(s.url))
            """<li><span class="badge">${if (xtream) "Xtream" else "M3U"}</span>
<div class="src"><strong>${esc(s.name)}</strong><span>$detail</span></div>
<form method="post" action="$base"><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="${esc(s.id)}">
<button class="btn ghost small" aria-label="Remove ${esc(s.name)}">Remove</button></form></li>"""
        }
        val sourceList = if (sources.isEmpty()) """<p class="empty">No sources yet. Add the Xtream login or M3U playlist from your IPTV provider.</p>"""
            else """<ul class="list">$sources</ul>"""
        val plexLinked = settings.plexToken.isNotBlank() && settings.plexUrl.isNotBlank()
        val plexChip = if (plexLinked) """<span class="chip on">Connected</span>""" else """<span class="chip">Not connected</span>"""
        val plexDetail = if (plexLinked) """<p class="muted">Server ${esc(hostOf(settings.plexUrl))}</p>"""
            else """<p class="muted">Easiest: open <strong>plex.tv/link</strong> and enter the code shown on the TV.</p>"""
        val tokenHint = if (settings.plexToken.isNotBlank()) "Saved. Leave empty to keep it." else "Not set"
        return """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Masal setup</title>
<link rel="icon" type="image/png" href="$base/favicon.png"><meta name="theme-color" content="#121110">
<style>
@font-face{font-family:"DM Sans";src:url("$base/dm-sans.ttf") format("truetype");font-weight:400 800;font-display:swap}
@font-face{font-family:"Fraunces";src:url("$base/fraunces.ttf") format("truetype");font-weight:400 800;font-display:swap}
:root{color-scheme:dark;--bg:#121110;--panel:#1e1c1a;--raised:#2c2824;--line:#36322e;--text:#f2eee8;--dim:#a8a29a;--accent:#e0a458;--on-accent:#1a1612;--ok:#8fbf7a;
--sans:"DM Sans",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;--word:"Fraunces",Georgia,serif}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 var(--sans);-webkit-text-size-adjust:100%}
main{max-width:560px;margin:0 auto;padding-block:28px 72px;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px));display:flex;flex-direction:column;gap:16px}
.top{display:flex;align-items:center;gap:12px}
.top img{width:48px;height:48px}
.word{font-family:var(--word);font-variation-settings:"SOFT" 100,"WONK" 0;font-weight:600;font-size:30px;line-height:1}
.sub{color:var(--dim);font-size:14px;letter-spacing:.04em;text-transform:uppercase;margin-top:4px}
.lead{color:var(--dim);margin:0;font-size:15px}
.notice{background:var(--raised);border:1px solid var(--accent);border-radius:12px;padding:12px 14px;font-weight:500}
.card{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:18px;display:flex;flex-direction:column;gap:12px}
.head{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
h2{font-size:18px;font-weight:700;margin:0}
.muted,.empty{color:var(--dim);margin:0;font-size:15px}
.list{list-style:none;margin:0;padding:0;display:flex;flex-direction:column}
.list li{display:flex;align-items:center;gap:12px;padding:12px 0;border-top:1px solid var(--line)}
.list li:first-child{border-top:0;padding-top:4px}
.src{flex:1;min-width:0;display:flex;flex-direction:column}
.src span{color:var(--dim);font-size:14px;overflow-wrap:anywhere}
.badge{flex:none;font-size:12px;font-weight:700;letter-spacing:.03em;color:var(--accent);border:1px solid var(--line);border-radius:999px;padding:3px 9px}
.chip{font-size:13px;font-weight:600;border-radius:999px;padding:4px 10px;background:var(--raised);color:var(--dim)}
.chip.on{color:var(--ok)}
label{display:block;font-size:14px;color:var(--dim);margin:0 0 6px}
.field{display:flex;flex-direction:column}
.fields{display:flex;flex-direction:column;gap:12px}
input[type=text],input[type=url],input[type=password]{width:100%;padding:12px 14px;border-radius:10px;border:1px solid var(--line);background:var(--bg);color:var(--text);font:inherit}
input:focus-visible,button:focus-visible,summary:focus-visible,.type:focus-within{outline:2px solid var(--accent);outline-offset:2px}
.btn{appearance:none;border:0;border-radius:999px;padding:11px 20px;background:var(--accent);color:var(--on-accent);font:inherit;font-weight:700;cursor:pointer}
.btn.ghost{background:transparent;color:var(--text);border:1px solid var(--line)}
.btn.small{padding:7px 14px;font-size:14px;font-weight:600}
.actions{display:flex;gap:10px;justify-content:flex-end;flex-wrap:wrap}
details{border-top:1px solid var(--line);padding-top:12px}
summary{cursor:pointer;color:var(--dim);font-size:15px}
details[open] summary{margin-bottom:12px}
dialog{width:min(520px,calc(100% - 24px));max-height:calc(100% - 24px);overflow:auto;border:1px solid var(--line);border-radius:18px;padding:20px;background:var(--panel);color:var(--text)}
dialog::backdrop{background:rgba(0,0,0,.65)}
dialog form{display:flex;flex-direction:column;gap:16px}
.types{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;border:0;margin:0;padding:0}
.types legend{font-size:14px;color:var(--dim);margin-bottom:8px;padding:0}
.type{position:relative;display:flex;flex-direction:column;gap:2px;border:1px solid var(--line);border-radius:12px;padding:12px;cursor:pointer;margin:0;color:var(--text)}
.type input{position:absolute;opacity:0;pointer-events:none}
.type strong{font-size:15px}
.type span{font-size:13px;color:var(--dim)}
.type:has(input:checked){border-color:var(--accent);background:var(--raised)}
fieldset.group{border:0;margin:0;padding:0;min-width:0}
fieldset.group[disabled]{display:none}
@media (max-width:380px){.types{grid-template-columns:1fr}}
@media (prefers-reduced-motion:no-preference){dialog[open]{animation:rise .18s ease-out}@keyframes rise{from{opacity:0;transform:translateY(8px)}}}
</style></head><body><main>
<header class="top"><img src="$base/logo.png" alt=""><div><div class="word">Masal</div><div class="sub">Setup</div></div></header>
<p class="lead">Changes reach the TV at once. This page works only while Settings is open on the TV.</p>
${notice?.let { "<div class=\"notice\" role=\"status\">${esc(it)}</div>" } ?: ""}
<section class="card" aria-labelledby="h-src">
<div class="head"><h2 id="h-src">Live TV sources</h2><button type="button" class="btn small" id="open-add">+ Add source</button></div>
$sourceList
</section>
<section class="card" aria-labelledby="h-row">
<h2 id="h-row">Home live TV row</h2>
<p class="muted">One channel category from your sources, shown as a row on Home.</p>
<form method="post" action="$base" class="fields"><input type="hidden" name="action" value="home">
<div class="field"><label for="row-group">Channel category (as in the TV tab; empty hides the row)</label><input type="text" id="row-group" name="group" value="${esc(settings.homeLiveGroup)}" placeholder="News HD" autocapitalize="off"></div>
<div class="field"><label for="row-title">Row title</label><input type="text" id="row-title" name="title" value="${esc(settings.homeLiveTitle)}" placeholder="Live TV"></div>
<div class="actions"><button class="btn">Save row</button></div>
</form>
</section>
<section class="card" aria-labelledby="h-plex">
<div class="head"><h2 id="h-plex">Plex</h2>$plexChip</div>
$plexDetail
<details><summary>Enter server and token manually</summary>
<form method="post" action="$base" class="fields"><input type="hidden" name="action" value="plex">
<div class="field"><label for="plex-url">Server URL</label><input type="url" id="plex-url" name="url" value="${esc(settings.plexUrl)}" placeholder="http://plex-server:32400" autocapitalize="off"></div>
<div class="field"><label for="plex-token">Token</label><input type="password" id="plex-token" name="token" placeholder="$tokenHint"></div>
<div class="actions"><button class="btn">Save Plex</button></div>
</form></details>
</section>
</main>
<dialog id="add" aria-labelledby="h-add">
<form method="post" action="$base">
<h2 id="h-add">Add source</h2>
<fieldset class="types"><legend>What did your provider give you?</legend>
<label class="type"><input type="radio" name="action" value="add_xtream" checked><strong>Xtream login</strong><span>Server, user name and password</span></label>
<label class="type"><input type="radio" name="action" value="add_m3u"><strong>M3U playlist</strong><span>A playlist link, optionally a guide</span></label>
</fieldset>
<fieldset class="group fields" data-for="add_xtream">
<div class="field"><label for="xt-name">Name</label><input type="text" id="xt-name" name="name" placeholder="My provider"></div>
<div class="field"><label for="xt-server">Server URL</label><input type="url" id="xt-server" name="server" placeholder="http://provider.example:8080" autocapitalize="off" autocorrect="off" required></div>
<div class="field"><label for="xt-user">User name</label><input type="text" id="xt-user" name="user" autocapitalize="off" autocorrect="off" autocomplete="off" required></div>
<div class="field"><label for="xt-pass">Password</label><input type="password" id="xt-pass" name="pass" autocomplete="off" required></div>
<div class="field"><label for="xt-epg">Guide URL (optional, default is the provider's xmltv.php)</label><input type="url" id="xt-epg" name="epg" autocapitalize="off"></div>
</fieldset>
<fieldset class="group fields" data-for="add_m3u" disabled>
<div class="field"><label for="m3u-name">Name</label><input type="text" id="m3u-name" name="name" placeholder="My playlist"></div>
<div class="field"><label for="m3u-url">Playlist URL</label><input type="url" id="m3u-url" name="url" placeholder="http://server:port/playlist.m3u" autocapitalize="off" autocorrect="off" required></div>
<div class="field"><label for="m3u-epg">Guide (XMLTV) URL, optional</label><input type="url" id="m3u-epg" name="epg" placeholder="http://server:port/epg.xml" autocapitalize="off"></div>
</fieldset>
<div class="actions"><button type="button" class="btn ghost" id="close-add">Cancel</button><button class="btn">Add source</button></div>
</form>
</dialog>
<script>
(function () {
  var dialog = document.getElementById("add");
  var groups = dialog.querySelectorAll("fieldset.group");
  function pick() {
    var chosen = dialog.querySelector("input[name=action]:checked").value;
    groups.forEach(function (g) { g.disabled = g.getAttribute("data-for") !== chosen; });
  }
  dialog.querySelectorAll("input[name=action]").forEach(function (r) { r.addEventListener("change", pick); });
  document.getElementById("open-add").addEventListener("click", function () {
    pick();
    if (dialog.showModal) dialog.showModal(); else dialog.setAttribute("open", "");
  });
  document.getElementById("close-add").addEventListener("click", function () {
    if (dialog.close) dialog.close(); else dialog.removeAttribute("open");
  });
})();
</script>
</body></html>"""
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
