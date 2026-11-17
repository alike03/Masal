package com.alike.masal.setup

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.os.LocaleList
import com.alike.masal.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.alike.masal.data.HomeRowOption
import com.alike.masal.data.IptvSource
import com.alike.masal.data.Language
import com.alike.masal.data.Settings
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

const val SETUP_PORT = 8765

/**
 * Temporary settings page on the local network. It only runs while the setup
 * screen is open and only answers under a random path that is shown in the QR code.
 */
class SetupServer(
    /** For the page's texts, in the language stored in the preferences (see [texts]). */
    private val context: Context,
    private val settings: Settings,
    private val key: String,
    private val onChanged: () -> Unit,
    /** Files the page uses (logo, favicon, fonts) by name: MIME type and bytes. Served under the same secret path. */
    private val assets: Map<String, Pair<String, ByteArray>> = emptyMap(),
    /** Channel categories of the loaded sources, in provider order, for the Home row select. */
    private val channelGroups: () -> List<String> = { emptyList() },
    /** Plex rows and collections that can be shown on Home. */
    private val homeRows: () -> List<HomeRowOption> = { emptyList() },
) : NanoHTTPD(SETUP_PORT) {

    override fun serve(session: IHTTPSession): Response {
        val base = "/$key"
        assets[session.uri.removePrefix("$base/")]?.takeIf { session.uri.startsWith("$base/") }?.let { (mime, bytes) ->
            return newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
                .apply { addHeader("Cache-Control", "max-age=3600") }
        }
        if (session.uri.trimEnd('/') != base) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", str(R.string.setup_not_found))
        }
        var notice: String? = null
        if (session.method == Method.POST) {
            session.parseBody(HashMap())
            val p = session.parameters.mapValues { it.value.firstOrNull().orEmpty().trim() }
            val (text, reload) = handle(p, session.parameters)
            notice = text
            // Sources and Plex reload everything; Home layout changes only redraw Home.
            if (reload) onChanged() else settings.homeChanged()
            val r = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "")
            r.addHeader("Location", "$base?saved=" + java.net.URLEncoder.encode(notice, "UTF-8"))
            return r
        }
        notice = session.parameters["saved"]?.firstOrNull()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page(base, notice))
    }

    /** A text in the current language (read again each time: the page itself can change the language). */
    private fun str(id: Int, vararg args: Any): String = texts(context).getString(id, *args)

    /** "http://host:port" of a URL, without path, query or user info; null when it cannot be read. */
    private fun hostOf(url: String): String? = runCatching {
        val u = java.net.URI(url.trim())
        "${u.scheme}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
    }.getOrNull()

    /** Applies a form; returns the notice and whether data must reload (false: only Home's layout changed). */
    private fun handle(p: Map<String, String>, all: Map<String, List<String>>): Pair<String, Boolean> = when (p["action"]) {
        "language" -> {
            // Stored like Language.set does; the TV activity switches when Settings opens again.
            val code = p["language"].orEmpty()
            if (Language.choices.any { it.first == code } && code != Language.stored(context)) {
                context.getSharedPreferences("masal", Context.MODE_PRIVATE).edit().putString("language", code).commit()
                if (code.isBlank()) Locale.setDefault(context.applicationContext.resources.configuration.locales[0])
            }
            str(R.string.setup_language_saved) to false
        }
        "home" -> {
            settings.homeLiveGroup = p["group"].orEmpty()
            settings.homeLiveTitle = p["title"].orEmpty()
            str(R.string.setup_saved_home_row) to false
        }
        "rows" -> {
            // Unchecked boxes are not sent: every row listed on the page comes along as "all".
            val listed = all["all"].orEmpty().toSet()
            val checked = all["row"].orEmpty().toSet()
            homeRows().filter { it.id in listed }.forEach { settings.setHomeRowShown(it, it.id in checked) }
            str(R.string.setup_saved_home_rows) to false
        }
        else -> handleData(p) to true
    }

    private fun handleData(p: Map<String, String>): String = when (p["action"]) {
        "plex" -> {
            if (p["url"].orEmpty().isNotBlank()) settings.plexUrl = p["url"]!!
            if (p["token"].orEmpty().isNotBlank()) settings.plexToken = p["token"]!!
            str(R.string.setup_saved_plex)
        }
        "add_m3u" -> {
            if (p["url"].isNullOrBlank()) str(R.string.setup_m3u_url_missing)
            else {
                settings.sources = settings.sources + IptvSource(
                    id = UUID.randomUUID().toString().take(8),
                    name = p["name"].orEmpty().ifBlank { str(R.string.setup_m3u_default_name) },
                    type = "m3u", url = p["url"]!!, epgUrl = p["epg"].orEmpty(),
                )
                str(R.string.setup_m3u_added)
            }
        }
        "add_xtream" -> {
            if (p["server"].isNullOrBlank() || p["user"].isNullOrBlank()) str(R.string.setup_xtream_missing)
            else {
                settings.sources = settings.sources + IptvSource(
                    id = UUID.randomUUID().toString().take(8),
                    name = p["name"].orEmpty().ifBlank { "IPTV" },
                    type = "xtream", server = p["server"]!!, user = p["user"]!!, pass = p["pass"].orEmpty(),
                    epgUrl = p["epg"].orEmpty(),
                )
                str(R.string.setup_xtream_added)
            }
        }
        "delete" -> {
            settings.sources = settings.sources.filterNot { it.id == p["id"] }
            str(R.string.setup_source_removed)
        }
        else -> str(R.string.setup_nothing_changed)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(base: String, notice: String?): String {
        val r = texts(context)
        /** A text from the resources, escaped for HTML. */
        fun t(id: Int, vararg args: Any): String = esc(r.getString(id, *args))
        fun host(url: String): String = hostOf(url) ?: r.getString(R.string.setup_address_hidden)
        val sources = settings.sources.joinToString("") { s ->
            // Only the host: playlist URLs and user names are half of the login, and anyone who sees the QR code can open this page.
            val xtream = s.type == "xtream"
            val detail = if (xtream) "${esc(host(s.server))} &middot; ${t(R.string.setup_user)} ${esc(s.user.take(2))}&hellip;" else esc(host(s.url))
            """<li><span class="badge">${if (xtream) "Xtream" else "M3U"}</span>
<div class="src"><strong>${esc(s.name)}</strong><span>$detail</span></div>
<form method="post" action="$base"><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="${esc(s.id)}">
<button class="btn ghost small" aria-label="${t(R.string.setup_remove_named, s.name)}">${t(R.string.setup_remove)}</button></form></li>"""
        }
        val sourceList = if (sources.isEmpty()) """<p class="empty">${t(R.string.setup_no_sources)}</p>"""
            else """<ul class="list">$sources</ul>"""
        val plexLinked = settings.plexToken.isNotBlank() && settings.plexUrl.isNotBlank()
        val plexChip = if (plexLinked) """<span class="chip on">${t(R.string.setup_connected)}</span>""" else """<span class="chip">${t(R.string.setup_not_connected)}</span>"""
        val plexDetail = if (plexLinked) """<p class="muted">${t(R.string.setup_plex_server, host(settings.plexUrl))}</p>"""
            else """<p class="muted">${t(R.string.setup_plex_easiest, "\u0001").replace("\u0001", "<strong>plex.tv/link</strong>")}</p>"""
        val tokenHint = t(if (settings.plexToken.isNotBlank()) R.string.setup_token_saved else R.string.setup_token_not_set)
        // Home row: pick one of the loaded categories. A saved category that is not (yet) in the list
        // stays selectable, so loading sources late or a renamed category never clears the setting.
        val groups = channelGroups()
        val saved = settings.homeLiveGroup
        val savedNorm = com.alike.masal.MasalViewModel.normGroup(saved)
        val savedInList = saved.isNotBlank() && groups.any { com.alike.masal.MasalViewModel.normGroup(it) == savedNorm }
        val groupOptions = buildString {
            append("<option value=\"\"${if (saved.isBlank()) " selected" else ""}>${t(R.string.settings_category_none)}</option>")
            if (saved.isNotBlank() && !savedInList) append("<option value=\"${esc(saved)}\" selected>${t(R.string.settings_category_missing, saved)}</option>")
            groups.forEach { g ->
                val sel = saved.isNotBlank() && com.alike.masal.MasalViewModel.normGroup(g) == savedNorm
                append("<option value=\"${esc(g)}\"${if (sel) " selected" else ""}>${esc(g)}</option>")
            }
        }
        val rowOptions = homeRows()
        val rowsBody = if (rowOptions.isEmpty()) """<p class="empty">${t(R.string.setup_rows_empty)}</p>"""
        else buildString {
            append("<form method=\"post\" action=\"$base\" class=\"fields\"><input type=\"hidden\" name=\"action\" value=\"rows\">")
            rowOptions.groupBy { it.group }.forEach { (group, options) ->
                append("<fieldset class=\"checks\"><legend>${esc(group)}</legend>")
                options.forEach { o ->
                    val id = "row-" + o.id.replace(Regex("[^A-Za-z0-9]"), "-")
                    append("<input type=\"hidden\" name=\"all\" value=\"${esc(o.id)}\">")
                    append("<label class=\"check\" for=\"$id\"><input type=\"checkbox\" id=\"$id\" name=\"row\" value=\"${esc(o.id)}\"" +
                        (if (settings.homeRowShown(o)) " checked" else "") + "><span>${esc(o.title)}</span></label>")
                }
                append("</fieldset>")
            }
            append("<div class=\"actions\"><button class=\"btn\">${t(R.string.setup_save_rows)}</button></div></form>")
        }
        val groupHint = if (groups.isEmpty()) t(R.string.setup_groups_empty) else t(R.string.setup_groups_count, groups.size)
        val storedLanguage = Language.stored(context)
        val languageOptions = Language.choices.joinToString("") { (code, name) ->
            "<option value=\"${esc(code)}\"${if (code == storedLanguage) " selected" else ""}>${esc(name ?: r.getString(R.string.language_device))}</option>"
        }
        return """<!doctype html><html lang="${t(R.string.setup_lang_code)}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${t(R.string.setup_page_title)}</title>
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
select{appearance:none;background-image:linear-gradient(45deg,transparent 50%,var(--dim) 50%),linear-gradient(135deg,var(--dim) 50%,transparent 50%);background-position:calc(100% - 20px) 55%,calc(100% - 14px) 55%;background-size:6px 6px;background-repeat:no-repeat;padding-right:40px}
.hint{color:var(--dim);font-size:13px;margin:6px 0 0}
input[type=text],input[type=url],input[type=password],select{width:100%;padding:12px 14px;border-radius:10px;border:1px solid var(--line);background-color:var(--bg);color:var(--text);font:inherit}
input:focus-visible,select:focus-visible,button:focus-visible,summary:focus-visible,.type:focus-within{outline:2px solid var(--accent);outline-offset:2px}
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
fieldset.checks{border:0;margin:0;padding:0;min-width:0;display:flex;flex-direction:column}
fieldset.checks legend{font-size:13px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:var(--dim);padding:0;margin-bottom:4px}
.check{display:flex;align-items:center;gap:12px;margin:0;padding:10px 0;border-top:1px solid var(--line);color:var(--text);font-size:15px;cursor:pointer}
.check:first-of-type{border-top:0}
.check input{width:20px;height:20px;margin:0;flex:none;accent-color:var(--accent)}
fieldset.group[disabled]{display:none}
@media (max-width:380px){.types{grid-template-columns:1fr}}
@media (prefers-reduced-motion:no-preference){dialog[open]{animation:rise .18s ease-out}@keyframes rise{from{opacity:0;transform:translateY(8px)}}}
</style></head><body><main>
<header class="top"><img src="$base/logo.png" alt=""><div><div class="word">Masal</div><div class="sub">${t(R.string.setup_subtitle)}</div></div></header>
<p class="lead">${t(R.string.setup_lead)}</p>
${notice?.let { "<div class=\"notice\" role=\"status\">${esc(it)}</div>" } ?: ""}
<section class="card" aria-labelledby="h-lang">
<h2 id="h-lang">${t(R.string.language_title)}</h2>
<p class="muted">${t(R.string.setup_language_lead)}</p>
<form method="post" action="$base" class="fields"><input type="hidden" name="action" value="language">
<select id="lang" name="language" aria-labelledby="h-lang">$languageOptions</select>
<div class="actions"><button class="btn">${t(R.string.setup_save_language)}</button></div>
</form>
</section>
<section class="card" aria-labelledby="h-src">
<div class="head"><h2 id="h-src">${t(R.string.settings_sources_heading)}</h2><button type="button" class="btn small" id="open-add">${t(R.string.setup_add_source_button)}</button></div>
$sourceList
</section>
<section class="card" aria-labelledby="h-row">
<h2 id="h-row">${t(R.string.setup_home_row_heading)}</h2>
<p class="muted">${t(R.string.setup_home_row_lead)}</p>
<form method="post" action="$base" class="fields"><input type="hidden" name="action" value="home">
<div class="field"><label for="row-group">${t(R.string.settings_channel_category)}</label><select id="row-group" name="group">$groupOptions</select><p class="hint">$groupHint</p></div>
<div class="field"><label for="row-title">${t(R.string.settings_row_title)}</label><input type="text" id="row-title" name="title" value="${esc(settings.homeLiveTitle)}" placeholder="${t(R.string.setup_row_title_placeholder)}"></div>
<div class="actions"><button class="btn">${t(R.string.setup_save_row)}</button></div>
</form>
</section>
<section class="card" aria-labelledby="h-rows">
<h2 id="h-rows">${t(R.string.settings_home_rows)}</h2>
<p class="muted">${t(R.string.setup_home_rows_lead)}</p>
$rowsBody
</section>
<section class="card" aria-labelledby="h-plex">
<div class="head"><h2 id="h-plex">Plex</h2>$plexChip</div>
$plexDetail
<details><summary>${t(R.string.setup_plex_manual)}</summary>
<form method="post" action="$base" class="fields"><input type="hidden" name="action" value="plex">
<div class="field"><label for="plex-url">${t(R.string.setup_server_url)}</label><input type="url" id="plex-url" name="url" value="${esc(settings.plexUrl)}" placeholder="http://plex-server:32400" autocapitalize="off"></div>
<div class="field"><label for="plex-token">${t(R.string.setup_token)}</label><input type="password" id="plex-token" name="token" placeholder="$tokenHint"></div>
<div class="actions"><button class="btn">${t(R.string.setup_save_plex)}</button></div>
</form></details>
</section>
</main>
<dialog id="add" aria-labelledby="h-add">
<form method="post" action="$base">
<h2 id="h-add">${t(R.string.setup_add_source)}</h2>
<fieldset class="types"><legend>${t(R.string.setup_types_legend)}</legend>
<label class="type"><input type="radio" name="action" value="add_xtream" checked><strong>${t(R.string.setup_xtream_title)}</strong><span>${t(R.string.setup_xtream_detail)}</span></label>
<label class="type"><input type="radio" name="action" value="add_m3u"><strong>${t(R.string.setup_m3u_title)}</strong><span>${t(R.string.setup_m3u_detail)}</span></label>
</fieldset>
<fieldset class="group fields" data-for="add_xtream">
<div class="field"><label for="xt-name">${t(R.string.setup_name)}</label><input type="text" id="xt-name" name="name" placeholder="${t(R.string.setup_name_provider_placeholder)}"></div>
<div class="field"><label for="xt-server">${t(R.string.setup_server_url)}</label><input type="url" id="xt-server" name="server" placeholder="http://provider.example:8080" autocapitalize="off" autocorrect="off" required></div>
<div class="field"><label for="xt-user">${t(R.string.setup_user_name)}</label><input type="text" id="xt-user" name="user" autocapitalize="off" autocorrect="off" autocomplete="off" required></div>
<div class="field"><label for="xt-pass">${t(R.string.setup_password)}</label><input type="password" id="xt-pass" name="pass" autocomplete="off" required></div>
<div class="field"><label for="xt-epg">${t(R.string.setup_xtream_epg)}</label><input type="url" id="xt-epg" name="epg" autocapitalize="off"></div>
</fieldset>
<fieldset class="group fields" data-for="add_m3u" disabled>
<div class="field"><label for="m3u-name">${t(R.string.setup_name)}</label><input type="text" id="m3u-name" name="name" placeholder="${t(R.string.setup_name_playlist_placeholder)}"></div>
<div class="field"><label for="m3u-url">${t(R.string.setup_playlist_url)}</label><input type="url" id="m3u-url" name="url" placeholder="http://server:port/playlist.m3u" autocapitalize="off" autocorrect="off" required></div>
<div class="field"><label for="m3u-epg">${t(R.string.setup_m3u_epg)}</label><input type="url" id="m3u-epg" name="epg" placeholder="http://server:port/epg.xml" autocapitalize="off"></div>
</fieldset>
<div class="actions"><button type="button" class="btn ghost" id="close-add">${t(R.string.settings_cancel)}</button><button class="btn">${t(R.string.setup_add_source)}</button></div>
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
        /**
         * [context] in the language stored in the preferences ("": the device's language). Unlike
         * the activity's own context it follows a change made on the phone page at once.
         */
        fun texts(context: Context): Context {
            val code = Language.stored(context)
            val locale = if (code.isBlank()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(code)
            val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(locale)) }
            return context.createConfigurationContext(config)
        }

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
