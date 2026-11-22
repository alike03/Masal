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
import com.alike.masal.data.HomeRows
import com.alike.masal.data.IptvSource
import com.alike.masal.data.Language
import com.alike.masal.data.Settings
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID

const val SETUP_PORT = 8765

/**
 * Settings page on the local network at `http://<tv-ip>:8765/` (setup.md). It runs while Masal
 * runs; only the PIN shown in Settings on the TV (or the QR code, which carries it) signs a
 * device in, which then keeps a session cookie.
 */
class SetupServer(
    /** For the page's texts, in the language stored in the preferences (see [texts]). */
    private val context: Context,
    private val settings: Settings,
    private val auth: SetupAuth,
    private val onChanged: () -> Unit,
    /** Files the page uses (logo, favicon, fonts) by name: MIME type and bytes. Served without sign-in. */
    private val assets: Map<String, Pair<String, ByteArray>> = emptyMap(),
    /** Channel categories of the loaded sources, in provider order, for the Home row select. */
    private val channelGroups: () -> List<String> = { emptyList() },
    /** Plex rows and collections that can be shown on Home. */
    private val homeRows: () -> List<HomeRowOption> = { emptyList() },
    /** Home's rows in their order, as (id, name). */
    private val homeOrder: () -> List<Pair<String, String>> = { emptyList() },
    /** Moves a channel category by one place; null resets to the provider's order. */
    private val moveCategory: (String?, Int) -> Unit = { _, _ -> },
) : NanoHTTPD(SETUP_PORT) {

    override fun serve(session: IHTTPSession): Response {
        val base = ""
        assets[session.uri.removePrefix("/")]?.let { (mime, bytes) ->
            return newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
                .apply { addHeader("Cache-Control", "max-age=3600") }
        }
        if (session.uri != "/") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", str(R.string.setup_not_found))
        }
        if (session.method == Method.POST) session.parseBody(HashMap())
        if (!auth.valid(session.cookies.read(SetupAuth.Cookie))) return signIn(session)
        var notice: String? = null
        if (session.method == Method.POST) {
            val p = session.parameters.mapValues { it.value.firstOrNull().orEmpty().trim() }
            val (text, reload) = handle(p, session.parameters)
            notice = text
            // Sources and Plex reload everything; Home layout changes only redraw Home.
            if (reload) onChanged() else settings.homeChanged()
            val r = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "")
            val section = sectionOf(p["action"])?.let { "&open=$it" }.orEmpty()
            r.addHeader("Location", "$base/?saved=" + java.net.URLEncoder.encode(notice, "UTF-8") + section)
            return r
        }
        notice = session.parameters["saved"]?.firstOrNull()
        val open = session.parameters["open"]?.firstOrNull()?.takeIf { o -> sections.any { it.first == o } }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page(base, notice, open))
    }

    /**
     * Not signed in: a PIN from the form signs the device in and sets the cookie; otherwise the PIN
     * page. The QR code's address (`/?pin=`) opens the PIN page with the PIN filled in: signing in
     * from the page itself keeps the cookie, which a phone drops when the QR scanner's app opened
     * the address (SameSite=Strict). The PIN never stays in the address bar.
     */
    private fun signIn(session: IHTTPSession): Response {
        val pin = session.parameters["pin"]?.firstOrNull()
        if (session.method != Method.POST) {
            val qrPin = pin?.filter { it.isDigit() }?.take(6)?.takeIf { it.length == 6 }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", signInPage(null, qrPin)).apply {
                addHeader("Cache-Control", "no-store")
            }
        }
        val error = when (val r = pin?.let { auth.login(it) }) {
            null -> null
            is SetupAuth.Result.Ok -> return newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "").apply {
                addHeader("Location", "/")
                addHeader("Set-Cookie", "${SetupAuth.Cookie}=${r.token}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict")
            }
            SetupAuth.Result.Wrong -> str(R.string.setup_login_wrong)
            is SetupAuth.Result.Locked -> str(R.string.setup_login_locked, r.seconds)
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", signInPage(error)).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun signInPage(error: String?, prefilled: String? = null): String {
        val r = texts(context)
        fun t(id: Int, vararg args: Any): String = esc(r.getString(id, *args))
        return """<!doctype html><html lang="${t(R.string.setup_lang_code)}"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${t(R.string.setup_page_title)}</title>
<link rel="icon" type="image/png" href="/favicon.png"><meta name="theme-color" content="#121110">
<style>
@font-face{font-family:"DM Sans";src:url("/dm-sans.ttf") format("truetype");font-weight:400 800;font-display:swap}
@font-face{font-family:"Fraunces";src:url("/fraunces.ttf") format("truetype");font-weight:400 800;font-display:swap}
:root{color-scheme:dark}
body{margin:0;background:#121110;color:#f2eee8;font:16px/1.5 "DM Sans",system-ui,sans-serif}
main{max-width:380px;margin:0 auto;padding:56px 16px;display:flex;flex-direction:column;gap:14px}
.top{display:flex;align-items:center;gap:12px}.top img{width:44px;height:44px}
.word{font:700 28px/1 "Fraunces",Georgia,serif}
p{margin:0;color:#a8a29a}.err{color:#e0a458}
input{font:600 28px/1.2 "DM Sans",system-ui,sans-serif;letter-spacing:.2em;text-align:center;box-sizing:border-box;
display:block;width:100%;max-width:220px;margin:0 auto;padding:10px 12px 10px calc(12px + .2em);
background:#1e1c1a;color:#f2eee8;border:1px solid #36322e;border-radius:10px}
input:focus{outline:2px solid #e0a458;border-color:transparent}
button{font:700 16px "DM Sans",system-ui,sans-serif;padding:12px;border:0;border-radius:999px;background:#e0a458;color:#1a1612;cursor:pointer}
</style></head><body><main>
<header class="top"><img src="/logo.png" alt=""><div class="word">Masal</div></header>
<h1 style="margin:8px 0 0;font-size:22px">${t(R.string.setup_login_title)}</h1>
<p>${t(R.string.setup_login_lead)}</p>
${error?.let { "<p class=\"err\" role=\"alert\">${esc(it)}</p>" }.orEmpty()}
<form method="post" action="/"><label for="pin" style="position:absolute;left:-9999px">${t(R.string.setup_pin_label)}</label>
<input id="pin" name="pin" inputmode="numeric" pattern="[0-9]*" maxlength="6" autocomplete="one-time-code"${if (prefilled != null) " value=\"$prefilled\"" else " autofocus"} required>
<button style="width:100%;margin-top:14px"${if (prefilled != null) " autofocus" else ""}>${t(R.string.setup_login_button)}</button></form>
${if (prefilled != null) "<script>history.replaceState(null,\"\",\"/\")</script>" else ""}
</main></body></html>"""
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
        // Seerr (seerr.md): checked before it is stored; changes nothing that needs a reload.
        "seerr" -> {
            val url = p["url"].orEmpty().ifBlank { settings.seerrUrl }
            val key = p["key"].orEmpty().ifBlank { settings.seerrKey }
            when {
                url.isBlank() || key.isBlank() -> str(R.string.setup_seerr_missing)
                else -> runCatching { com.alike.masal.Graph.seerr.me(url, key) }.fold(
                    { user ->
                        settings.seerrUrl = url; settings.seerrKey = key; settings.seerrUserName = user.name
                        str(R.string.setup_seerr_saved, user.name)
                    },
                    { e ->
                        if (e is com.alike.masal.data.SeerrException && (e.code == 401 || e.code == 403)) str(R.string.setup_seerr_refused)
                        else str(R.string.setup_seerr_failed, e.message.orEmpty())
                    },
                )
            } to false
        }
        "seerr_remove" -> {
            settings.clearSeerr()
            com.alike.masal.Graph.seerr.cache.clear()
            str(R.string.setup_seerr_removed) to false
        }
        // Trailer service (seerr.md): checked before it is stored; an empty address removes it.
        "trailer_service" -> {
            val url = p["url"].orEmpty().trim().trimEnd('/')
            val text = if (url.isBlank()) {
                settings.trailerService = ""
                str(R.string.setup_trailer_removed)
            } else com.alike.masal.Graph.trailers.check(url).fold(
                { settings.trailerService = url; str(R.string.setup_trailer_saved) },
                { str(R.string.setup_trailer_failed, it.message.orEmpty()) },
            )
            text to false
        }
        "home_move", "cat_move" -> {
            // "up:<id>" or "down:<id>", from the button pressed.
            val move = p["move"].orEmpty()
            val delta = if (move.startsWith("up:")) -1 else 1
            val id = move.substringAfter(':')
            if (p["action"] == "cat_move") moveCategory(id, delta)
            else settings.setHomeOrder(HomeRows.move(homeOrder().map { it.first }, settings.homeOrder.value, id, delta))
            str(R.string.setup_saved_order) to false
        }
        "home_order_reset" -> { settings.setHomeOrder(emptyList()); str(R.string.setup_saved_order) to false }
        "cat_reset" -> { moveCategory(null, 0); str(R.string.setup_saved_order) to false }
        "player" -> {
            playerEntries(texts(context), settings).forEach { e ->
                p[e.key]?.takeIf { v -> e.choices.any { it.first == v } }?.let(e.store)
            }
            settings.homeChanged()
            str(R.string.setup_saved_player) to false
        }
        "seerr_prefs" -> {
            p["language"]?.takeIf { code -> code.isEmpty() || SeerrLanguages.any { it.first == code } }?.let { settings.seerrLanguage = it }
            val users = runCatching { com.alike.masal.Graph.seerr.users() }.getOrNull()
            if (users != null) (listOf("") + settings.profiles.map { it.id }).forEach { k ->
                val v = p["user:$k"] ?: return@forEach
                val chosen = users.firstOrNull { it.id.toString() == v }
                settings.seerrUsers = if (chosen == null) settings.seerrUsers - k else settings.seerrUsers + (k to (chosen.id to chosen.name))
            }
            settings.homeChanged() // Home's Seerr rows load in the new language
            str(R.string.setup_saved_seerr_prefs) to false
        }
        "seerr_cache" -> { com.alike.masal.Graph.seerr.cache.clear(); str(R.string.settings_seerr_cache_cleared) to false }
        // Signs every device out, this one too: the next page is the PIN page.
        "new_pin" -> { settings.renewSetupPin(); str(R.string.setup_new_pin_done) to false }
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

    /** The page's sections, in the TV's order (setup.md): id and title. */
    private val sections = listOf(
        "home" to R.string.settings_section_home, "live" to R.string.settings_section_live,
        "player" to R.string.settings_section_player, "accounts" to R.string.settings_section_accounts,
        "phone" to R.string.settings_phone_heading, "general" to R.string.settings_section_general,
    )

    /** The section a form belongs to: it stays open after saving. */
    private fun sectionOf(action: String?): String? = when (action) {
        "rows", "home", "home_move", "home_order_reset" -> "home"
        "cat_move", "cat_reset", "delete", "add_m3u", "add_xtream" -> "live"
        "player" -> "player"
        "plex", "seerr", "seerr_remove", "seerr_prefs", "seerr_cache", "trailer_service" -> "accounts"
        "language" -> "general"
        else -> null
    }

    private fun page(base: String, notice: String?, openSection: String?): String {
        val r = texts(context)
        /** A text from the resources, escaped for HTML. */
        fun t(id: Int, vararg args: Any): String = esc(r.getString(id, *args))
        fun plural(id: Int, n: Int): String = esc(r.resources.getQuantityString(id, n, n))
        fun host(url: String): String = hostOf(url) ?: r.getString(R.string.setup_address_hidden)
        fun chip(on: Boolean) = if (on) """<span class="chip on">${t(R.string.setup_connected)}</span>""" else """<span class="chip">${t(R.string.setup_not_connected)}</span>"""
        fun profileName(key: String) = when (key) {
            SeerrRowsKey -> HomeRows.SeerrGroup
            "" -> settings.plexAccountName.ifBlank { r.getString(R.string.settings_rows_main_account) }
            else -> settings.profiles.firstOrNull { it.id == key }?.name ?: key
        }
        /** A list in its order with ▲ ▼ per entry, as the TV's order pages (one form, the button says what moves). */
        fun orderList(action: String, entries: List<Pair<String, String>>): String = buildString {
            append("<form method=\"post\" action=\"$base/\"><input type=\"hidden\" name=\"action\" value=\"$action\"><ol class=\"order\">")
            entries.forEachIndexed { i, (id, name) ->
                append("<li><span>${esc(name)}</span>")
                append("<button class=\"btn ghost small\" name=\"move\" value=\"up:${esc(id)}\"${if (i == 0) " disabled" else ""} aria-label=\"${t(R.string.setup_move_up, name)}\">&#9650;</button>")
                append("<button class=\"btn ghost small\" name=\"move\" value=\"down:${esc(id)}\"${if (i == entries.lastIndex) " disabled" else ""} aria-label=\"${t(R.string.setup_move_down, name)}\">&#9660;</button></li>")
            }
            append("</ol></form>")
        }
        fun resetButton(action: String, label: Int) =
            """<form method="post" action="$base/"><input type="hidden" name="action" value="$action"><div class="actions"><button class="btn ghost small">${t(label)}</button></div></form>"""

        // ---- Home screen ----
        val order = homeOrder()
        val homeCustom = settings.homeOrder.value.isNotEmpty()
        val rowOptions = homeRows()
        val rowsBody = if (rowOptions.isEmpty()) """<p class="empty">${t(R.string.setup_rows_empty)}</p>"""
        else buildString {
            append("<form method=\"post\" action=\"$base/\" class=\"fields\"><input type=\"hidden\" name=\"action\" value=\"rows\">")
            // One folded block per profile and one for Seerr, like the TV's "Rows: <name>" entries (profiles.md, seerr.md).
            rowOptions.groupBy { rowsKey(it) }.forEach { (key, profileRows) ->
                val on = profileRows.count { settings.homeRowShown(it) }
                append("<details class=\"rows\"><summary>${t(R.string.settings_rows_for, profileName(key))} " +
                    "<span class=\"muted\">(${t(R.string.settings_home_rows_count, on, profileRows.size)})</span></summary>")
                profileRows.groupBy { it.group }.forEach { (group, options) ->
                    append("<fieldset class=\"checks\"><legend>${esc(group)}</legend>")
                    options.forEach { o ->
                        val id = "row-" + o.id.replace(Regex("[^A-Za-z0-9]"), "-")
                        append("<input type=\"hidden\" name=\"all\" value=\"${esc(o.id)}\">")
                        append("<label class=\"check\" for=\"$id\"><input type=\"checkbox\" id=\"$id\" name=\"row\" value=\"${esc(o.id)}\"" +
                            (if (settings.homeRowShown(o)) " checked" else "") + "><span>${esc(o.title)}</span></label>")
                    }
                    append("</fieldset>")
                }
                append("</details>")
            }
            append("<div class=\"actions\"><button class=\"btn\">${t(R.string.setup_save_rows)}</button></div></form>")
        }
        // Live TV row: one of the loaded categories. A saved category that is not (yet) in the list
        // stays selectable, so loading sources late or a renamed category never clears the setting.
        val groups = channelGroups()
        val saved = settings.homeLiveGroup
        val savedNorm = com.alike.masal.MasalViewModel.normGroup(saved)
        val favourites = Settings.FavouritesGroup
        val savedInList = saved == favourites || saved.isNotBlank() && groups.any { com.alike.masal.MasalViewModel.normGroup(it) == savedNorm }
        val groupOptions = buildString {
            append("<option value=\"\"${if (saved.isBlank()) " selected" else ""}>${t(R.string.settings_category_none)}</option>")
            append("<option value=\"${esc(favourites)}\"${if (saved == favourites) " selected" else ""}>${t(R.string.common_favourites)}</option>")
            if (saved.isNotBlank() && !savedInList) append("<option value=\"${esc(saved)}\" selected>${t(R.string.settings_category_missing, saved)}</option>")
            groups.forEach { g ->
                val sel = saved.isNotBlank() && saved != favourites && com.alike.masal.MasalViewModel.normGroup(g) == savedNorm
                append("<option value=\"${esc(g)}\"${if (sel) " selected" else ""}>${esc(g)}</option>")
            }
        }
        val groupHint = if (groups.isEmpty()) t(R.string.setup_groups_empty) else t(R.string.setup_groups_count, groups.size)
        val home = """
<h3>${t(R.string.settings_home_rows)}</h3>
<p class="muted">${t(R.string.setup_home_rows_lead)}</p>
$rowsBody
<h3>${t(R.string.settings_home_order)} <span class="muted">(${t(if (homeCustom) R.string.settings_category_order_custom else R.string.settings_category_order_default)})</span></h3>
${if (order.isEmpty()) """<p class="empty">${t(R.string.settings_home_order_empty)}</p>""" else orderList("home_move", order)}
${if (homeCustom) resetButton("home_order_reset", R.string.settings_home_order_reset) else ""}
<h3>${t(R.string.settings_live_row)}</h3>
<p class="muted">${t(R.string.setup_home_row_lead)}</p>
<form method="post" action="$base/" class="fields"><input type="hidden" name="action" value="home">
<div class="field"><label for="row-group">${t(R.string.settings_channel_category)}</label><select id="row-group" name="group">$groupOptions</select><p class="hint">$groupHint</p></div>
<div class="field"><label for="row-title">${t(R.string.settings_live_row_title)}</label><input type="text" id="row-title" name="title" value="${esc(settings.homeLiveTitle)}" placeholder="${t(R.string.setup_row_title_placeholder)}"></div>
<div class="actions"><button class="btn">${t(R.string.setup_save_row)}</button></div>
</form>"""

        // ---- Live TV ----
        val catCustom = settings.categoryOrder.isNotEmpty()
        val sources = settings.sources.joinToString("") { s ->
            // Only the host: playlist URLs and user names are half of the login, and anyone who sees the QR code can open this page.
            val xtream = s.type == "xtream"
            val detail = if (xtream) "${esc(host(s.server))} &middot; ${t(R.string.setup_user)} ${esc(s.user.take(2))}&hellip;" else esc(host(s.url))
            """<li><span class="badge">${if (xtream) "Xtream" else "M3U"}</span>
<div class="src"><strong>${esc(s.name)}</strong><span>$detail</span></div>
<form method="post" action="$base/"><input type="hidden" name="action" value="delete"><input type="hidden" name="id" value="${esc(s.id)}">
<button class="btn ghost small" aria-label="${t(R.string.setup_remove_named, s.name)}">${t(R.string.setup_remove)}</button></form></li>"""
        }
        val live = """
<h3>${t(R.string.settings_category_order)} <span class="muted">(${t(if (catCustom) R.string.settings_category_order_custom else R.string.settings_category_order_default)})</span></h3>
${if (groups.isEmpty()) """<p class="empty">${t(R.string.settings_category_order_empty)}</p>""" else orderList("cat_move", groups.map { it to it })}
${if (catCustom) resetButton("cat_reset", R.string.settings_category_order_reset) else ""}
<div class="head"><h3>${t(R.string.settings_sources_heading)}</h3><button type="button" class="btn small" id="open-add">${t(R.string.setup_add_source_button)}</button></div>
${if (sources.isEmpty()) """<p class="empty">${t(R.string.setup_no_sources)}</p>""" else """<ul class="list">$sources</ul>"""}"""

        // ---- Player ----
        val entries = com.alike.masal.setup.playerEntries(r, settings)
        val player = buildString {
            append("<form method=\"post\" action=\"$base/\" class=\"fields\"><input type=\"hidden\" name=\"action\" value=\"player\">")
            entries.forEach { e ->
                append("<div class=\"field\"><label for=\"pl-${e.key}\">${t(e.label)}</label><select id=\"pl-${e.key}\" name=\"${e.key}\">")
                e.choices.forEach { (v, label) -> append("<option value=\"${esc(v)}\"${if (v == e.current) " selected" else ""}>${esc(label)}</option>") }
                append("</select></div>")
            }
            append("<p class=\"hint\">${t(R.string.player_settings_note)}</p>")
            append("<div class=\"actions\"><button class=\"btn\">${t(R.string.setup_save_player)}</button></div></form>")
        }

        // ---- Accounts ----
        val tokenHint = t(if (settings.plexToken.isNotBlank()) R.string.setup_token_saved else R.string.setup_token_not_set)
        val plexLinked = settings.plexToken.isNotBlank() && settings.plexUrl.isNotBlank()
        val plexDetail = if (plexLinked) """<p class="muted">${t(R.string.setup_plex_server, host(settings.plexUrl))}</p>"""
            else """<p class="muted">${t(R.string.setup_plex_easiest, "\u0001").replace("\u0001", "<strong>plex.tv/link</strong>")}</p>"""
        // Profiles are added on the TV (profiles.md); the page only names them. esc() for the names.
        val profileNames = settings.profiles.joinToString(", ") { it.name }
        val seerrSet = settings.seerrUrl.isNotBlank() && settings.seerrKey.isNotBlank()
        val seerrPrefs = if (!seerrSet) "" else buildString {
            // Seerr's users for the request preselection per profile; without them only the language.
            val users = runCatching { com.alike.masal.Graph.seerr.users() }.getOrNull()
            append("<form method=\"post\" action=\"$base/\" class=\"fields\"><input type=\"hidden\" name=\"action\" value=\"seerr_prefs\">")
            append("<div class=\"field\"><label for=\"seerr-lang\">${t(R.string.settings_seerr_language)}</label><select id=\"seerr-lang\" name=\"language\">")
            (listOf("" to r.getString(R.string.settings_seerr_language_app)) + SeerrLanguages).forEach { (code, name) ->
                append("<option value=\"${esc(code)}\"${if (code == settings.seerrLanguage) " selected" else ""}>${esc(name)}</option>")
            }
            append("</select><p class=\"hint\">${t(R.string.settings_seerr_language_note)}</p></div>")
            append("<p class=\"muted\">${t(R.string.settings_seerr_note)}</p>")
            if (users == null) append("<p class=\"hint\">${t(R.string.settings_seerr_users_failed)}</p>")
            else (listOf("") + settings.profiles.map { it.id }).forEach { k ->
                val auto = com.alike.masal.Graph.seerr.matchUser(k, users)?.name
                val current = settings.seerrUsers[k]?.first?.toString().orEmpty()
                append("<div class=\"field\"><label for=\"su-$k\">${esc(profileName(k))}</label><select id=\"su-$k\" name=\"user:$k\">")
                append("<option value=\"\"${if (current.isEmpty()) " selected" else ""}>${auto?.let { t(R.string.settings_seerr_auto_user, it) } ?: t(R.string.settings_seerr_auto)}</option>")
                users.forEach { u -> append("<option value=\"${u.id}\"${if (u.id.toString() == current) " selected" else ""}>${esc(u.name)}</option>") }
                append("</select></div>")
            }
            append("<div class=\"actions\"><button class=\"btn\">${t(R.string.setup_save_seerr_prefs)}</button></div></form>")
            append(resetButton("seerr_cache", R.string.settings_seerr_clear_cache))
        }
        // The key is never sent back to the page: the field only says whether one is stored.
        val accounts = """
<div class="head"><h3>Plex</h3>${chip(plexLinked)}</div>
$plexDetail
<details><summary>${t(R.string.setup_plex_manual)}</summary>
<form method="post" action="$base/" class="fields"><input type="hidden" name="action" value="plex">
<div class="field"><label for="plex-url">${t(R.string.setup_server_url)}</label><input type="url" id="plex-url" name="url" value="${esc(settings.plexUrl)}" placeholder="http://plex-server:32400" autocapitalize="off"></div>
<div class="field"><label for="plex-token">${t(R.string.setup_token)}</label><input type="password" id="plex-token" name="token" placeholder="$tokenHint"></div>
<div class="actions"><button class="btn">${t(R.string.setup_save_plex)}</button></div>
</form></details>
<h3>${t(R.string.settings_profiles)}</h3>
<p class="muted">${esc(profileNames.ifBlank { r.getString(R.string.settings_profiles_value_none) })}</p>
<p class="hint">${t(R.string.setup_profiles_on_tv)}</p>
<div class="head"><h3>Seerr</h3>${chip(seerrSet)}</div>
<p class="muted">${if (seerrSet) t(R.string.setup_seerr_connected, settings.seerrUserName, host(settings.seerrUrl)) else t(R.string.setup_seerr_lead)}</p>
<form method="post" action="$base/" class="fields"><input type="hidden" name="action" value="seerr">
<div class="field"><label for="seerr-url">${t(R.string.setup_seerr_url)}</label><input type="url" id="seerr-url" name="url" value="${esc(settings.seerrUrl)}" placeholder="http://seerr:5055" autocapitalize="off" autocorrect="off"></div>
<div class="field"><label for="seerr-key">${t(R.string.setup_seerr_key)}</label><input type="password" id="seerr-key" name="key" autocomplete="off" placeholder="${t(if (settings.seerrKey.isNotBlank()) R.string.setup_token_saved else R.string.setup_token_not_set)}"></div>
<p class="hint">${t(R.string.setup_seerr_key_hint)}</p>
<div class="actions"><button class="btn">${t(R.string.setup_seerr_save)}</button></div>
</form>${if (seerrSet) """
<form method="post" action="$base/"><input type="hidden" name="action" value="seerr_remove"><div class="actions"><button class="btn ghost small">${t(R.string.setup_seerr_remove)}</button></div></form>""" else ""}
$seerrPrefs
<h3>${t(R.string.setup_trailer_title)}</h3>
<p class="muted">${t(R.string.setup_trailer_lead)}</p>
<form method="post" action="$base/" class="fields"><input type="hidden" name="action" value="trailer_service">
<div class="field"><label for="trailer-url">${t(R.string.setup_trailer_url)}</label><input type="url" id="trailer-url" name="url" value="${esc(settings.trailerService)}" placeholder="http://server:8791" autocapitalize="off" autocorrect="off"></div>
<div class="actions"><button class="btn">${t(R.string.setup_trailer_save)}</button></div>
</form>"""

        // ---- Phone page ----
        val address = "http://${localIp()}:$SETUP_PORT"
        val phone = """
<p class="muted">${esc(address)}</p>
<form method="post" action="$base/"><input type="hidden" name="action" value="new_pin">
<div class="actions"><button class="btn ghost">${t(R.string.setup_new_pin)}</button></div></form>
<p class="hint">${t(R.string.setup_new_pin_hint)}</p>"""

        // ---- General ----
        val storedLanguage = Language.stored(context)
        val languageOptions = Language.choices.joinToString("") { (code, name) ->
            "<option value=\"${esc(code)}\"${if (code == storedLanguage) " selected" else ""}>${esc(name ?: r.getString(R.string.language_device))}</option>"
        }
        val general = """
<form method="post" action="$base/" class="fields"><input type="hidden" name="action" value="language">
<div class="field"><label for="lang">${t(R.string.language_title)}</label><select id="lang" name="language">$languageOptions</select>
<p class="hint">${t(R.string.setup_language_lead)}</p></div>
<div class="actions"><button class="btn">${t(R.string.setup_save_language)}</button></div>
</form>"""

        // Each section's short value, as on the TV's start page.
        val values = mapOf(
            "home" to plural(R.plurals.settings_home_value, order.size),
            "live" to if (settings.sources.isEmpty()) t(R.string.settings_sources_none) else plural(R.plurals.settings_live_value, settings.sources.size),
            "player" to t(R.string.settings_player_value, settings.subtitleSize, settings.skipBackSeconds, settings.skipForwardSeconds),
            "accounts" to esc(listOfNotNull(
                r.getString(if (plexLinked) R.string.settings_accounts_plex_on else R.string.settings_accounts_plex_off),
                if (seerrSet) "Seerr" else null,
            ).joinToString(", ")),
            "phone" to esc(address),
            "general" to esc(Language.choices.firstOrNull { it.first == storedLanguage }?.second ?: r.getString(R.string.language_device)),
        )
        val abouts = mapOf(
            "home" to R.string.settings_section_home_about, "live" to R.string.settings_section_live_about,
            "player" to R.string.settings_section_player_about, "accounts" to R.string.settings_section_accounts_about,
            "phone" to R.string.setup_section_phone_about, "general" to R.string.setup_section_general_about,
        )
        val bodies = mapOf("home" to home, "live" to live, "player" to player, "accounts" to accounts, "phone" to phone, "general" to general)
        val sectionHtml = sections.joinToString("\n") { (id, title) ->
            """<details class="section card" id="s-$id"${if (id == openSection) " open" else ""}>
<summary><span class="stitle">${t(title)}</span><span class="svalue">${values.getValue(id)}</span></summary>
<div class="sbody"><p class="muted">${t(abouts.getValue(id))}</p>${bodies.getValue(id)}</div>
</details>"""
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
h3{font-size:16px;font-weight:700;margin:22px 0 0}
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
details.rows summary{color:var(--text);font-size:17px;font-weight:600}
details.section{padding:0;gap:0;border-top:1px solid var(--line)}
details.section>summary{list-style:none;display:flex;align-items:baseline;justify-content:space-between;gap:12px;padding:16px 18px;margin:0;color:var(--text)}
details.section>summary::-webkit-details-marker{display:none}
details.section>summary::after{content:"\203A";color:var(--dim);font-size:22px;line-height:1;transition:transform .15s}
details.section[open]>summary::after{transform:rotate(90deg)}
.stitle{font-size:18px;font-weight:700;flex:none}
.svalue{flex:1;text-align:right;color:var(--dim);font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sbody{display:flex;flex-direction:column;gap:12px;padding:0 18px 18px}
.sbody h3{margin:14px 0 0}
ol.order{list-style:none;margin:0;padding:0;counter-reset:o}
ol.order li{display:flex;align-items:center;gap:8px;padding:8px 0;border-top:1px solid var(--line);counter-increment:o}
ol.order li:first-child{border-top:0}
ol.order li span{flex:1;min-width:0;overflow-wrap:anywhere}
ol.order li span::before{content:counter(o) ". ";color:var(--dim)}
ol.order button{min-width:44px;min-height:40px}
ol.order button:disabled{opacity:.3;cursor:default}
details.rows summary .muted{font-weight:400;font-size:14px}
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
$sectionHtml
</main>
<dialog id="add" aria-labelledby="h-add">
<form method="post" action="$base/">
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
