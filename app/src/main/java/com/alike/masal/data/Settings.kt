package com.alike.masal.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One IPTV source: either a plain M3U playlist or an Xtream Codes login. */
data class IptvSource(
    val id: String,
    val name: String,
    val type: String, // "m3u" or "xtream"
    val url: String = "",
    val epgUrl: String = "",
    val server: String = "",
    val user: String = "",
    val pass: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("type", type)
        .put("url", url).put("epgUrl", epgUrl)
        .put("server", server).put("user", user).put("pass", pass)

    companion object {
        fun fromJson(o: JSONObject) = IptvSource(
            id = o.optString("id"),
            name = o.optString("name"),
            type = o.optString("type", "m3u"),
            url = o.optString("url"),
            epgUrl = o.optString("epgUrl"),
            server = o.optString("server"),
            user = o.optString("user"),
            pass = o.optString("pass"),
        )
    }
}

/**
 * App settings in private SharedPreferences. Credentials stay on the device;
 * they are only entered through the temporary QR setup page.
 */
class Settings(context: Context) {
    companion object {
        /** [homeLiveGroup] value for the favourite channels; no provider category looks like this. */
        const val FavouritesGroup = ":favourites"
    }

    private val prefs = context.getSharedPreferences("masal", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    /** Bumped whenever sources or Plex settings change, so data gets reloaded. */
    val version: StateFlow<Int> = _version

    private val _homeVersion = MutableStateFlow(0)
    /** Bumped when only the Home layout changes (live TV row, Plex rows): Home redraws, nothing reloads. */
    val homeVersion: StateFlow<Int> = _homeVersion

    val clientId: String
        get() = prefs.getString("client_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("client_id", it).apply() }

    var plexUrl: String
        get() = prefs.getString("plex_url", "") ?: ""
        set(v) = prefs.edit().putString("plex_url", v.trim().trimEnd('/')).apply()

    var plexToken: String
        get() = prefs.getString("plex_token", "") ?: ""
        set(v) = prefs.edit().putString("plex_token", v.trim()).apply()

    var sources: List<IptvSource>
        get() {
            val raw = prefs.getString("sources", "[]") ?: "[]"
            val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            return (0 until arr.length()).map { IptvSource.fromJson(arr.getJSONObject(it)) }
        }
        set(v) {
            val arr = JSONArray().apply { v.forEach { put(it.toJson()) } }
            prefs.edit().putString("sources", arr.toString()).apply()
        }

    var favorites: Set<String>
        get() = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("favorites", v).apply()

    /** Most recently watched channel keys, newest first. */
    var recentChannels: List<String>
        get() {
            val arr = runCatching { JSONArray(prefs.getString("recent_channels", "[]")) }.getOrDefault(JSONArray())
            return (0 until arr.length()).map { arr.getString(it) }
        }
        set(v) = prefs.edit().putString("recent_channels", JSONArray(v).toString()).apply()

    /** Apps pinned to the Home row, in the user's order. */
    var homeApps: List<String>
        get() {
            val arr = runCatching { JSONArray(prefs.getString("home_apps", "[]")) }.getOrDefault(JSONArray())
            return (0 until arr.length()).map { arr.getString(it) }
        }
        set(v) = prefs.edit().putString("home_apps", JSONArray(v).toString()).apply()

    /** Channel categories in the user's order (exact names); categories not listed follow in the provider's order. */
    var categoryOrder: List<String>
        get() {
            val arr = runCatching { JSONArray(prefs.getString("category_order", "[]")) }.getOrDefault(JSONArray())
            return (0 until arr.length()).map { arr.getString(it) }
        }
        set(v) = prefs.edit().putString("category_order", JSONArray(v).toString()).apply()

    /**
     * IPTV category shown as a row on Home (matched without accents and provider prefix); empty hides
     * the row, [FavouritesGroup] shows the favourite channels.
     */
    var homeLiveGroup: String
        get() = prefs.getString("home_live_group", "") ?: ""
        set(v) = prefs.edit().putString("home_live_group", v.trim()).apply()

    /** Title of that Home row; without an own title "Live TV" in the app's language. */
    var homeLiveTitle: String
        get() = prefs.getString("home_live_title", "")?.ifBlank { null }
            ?: runCatching { Language.text(com.alike.masal.R.string.common_live_tv) }.getOrDefault("Live TV")
        set(v) = prefs.edit().putString("home_live_title", v.trim()).apply()

    /** Name of the linked Plex account, to name its rows in Settings next to the profiles. */
    var plexAccountName: String
        get() = prefs.getString("plex_account_name", "") ?: ""
        set(v) = prefs.edit().putString("plex_account_name", v).apply()

    /** Plex Home members added as profiles, in the order they were added (profiles.md). Holds their tokens. */
    var profiles: List<PlexProfile>
        get() = runCatching {
            val a = JSONArray(prefs.getString("plex_profiles", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { PlexProfile(it.getString("id"), it.getString("name"), it.getString("token")) }
        }.getOrDefault(emptyList())
        set(v) = prefs.edit().putString("plex_profiles", JSONArray().apply {
            v.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("token", it.token)) }
        }.toString()).apply()

    /** Profiles whose token the server refused; Settings offers to sign in again. */
    var profilesRejected: Set<String>
        get() = prefs.getStringSet("plex_profiles_rejected", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("plex_profiles_rejected", v).apply()

    fun saveProfile(p: PlexProfile) {
        val list = profiles
        profiles = if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
        profilesRejected = profilesRejected - p.id
    }

    /** Removes the profile, its token and its Home row choices. */
    fun removeProfile(id: String) {
        profiles = profiles.filter { it.id != id }
        profilesRejected = profilesRejected - id
        val prefix = HomeRows.profilePrefix(id)
        homeRowsOn = homeRowsOn.filterTo(HashSet()) { !it.startsWith(prefix) }
        homeRowsOff = homeRowsOff.filterTo(HashSet()) { !it.startsWith(prefix) }
        seerrUsers = seerrUsers - id
    }

    /** Home rows switched on although Plex does not show them on its Home (see [homeRowShown]). */
    var homeRowsOn: Set<String>
        get() = prefs.getStringSet("home_rows_on", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("home_rows_on", v).apply()

    /** Home rows switched off although Plex shows them on its Home. */
    var homeRowsOff: Set<String>
        get() = prefs.getStringSet("home_rows_off", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("home_rows_off", v).apply()

    /** Stored as differences from Plex's own Home, so rows Plex adds later still appear by default. */
    fun homeRowShown(option: HomeRowOption): Boolean =
        option.id in homeRowsOn || (option.defaultOn && option.id !in homeRowsOff)

    private val _homeOrder = MutableStateFlow(readHomeOrder())
    /**
     * Home's row order (home.md, "Row order"): row ids, also of rows switched off for now, so they
     * keep their place. Empty: the default order. Changing it redraws Home without reloading rows.
     */
    val homeOrder: StateFlow<List<String>> = _homeOrder

    private fun readHomeOrder() = prefs.getString("home_order", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun setHomeOrder(ids: List<String>) {
        prefs.edit().putString("home_order", ids.joinToString("\n")).apply()
        _homeOrder.value = ids
    }

    fun setHomeRowShown(option: HomeRowOption, shown: Boolean) {
        homeRowsOn = if (shown && !option.defaultOn) homeRowsOn + option.id else homeRowsOn - option.id
        homeRowsOff = if (!shown && option.defaultOn) homeRowsOff + option.id else homeRowsOff - option.id
    }

    // ---- Phone setup page (setup.md) ----

    /** The setup page's PIN, six digits, made once; never logged or printed. */
    val setupPin: String
        get() = prefs.getString("setup_pin", null)
            ?: newSetupPinValue().also { prefs.edit().putString("setup_pin", it).apply() }

    /** A new PIN; every signed-in phone or computer has to enter it again. */
    fun renewSetupPin(): String {
        val pin = newSetupPinValue()
        prefs.edit().putString("setup_pin", pin).remove("setup_sessions").apply()
        return pin
    }

    private fun newSetupPinValue() = "%06d".format(java.security.SecureRandom().nextInt(1_000_000))

    /** SHA-256 of the session cookies of signed-in devices, newest last (at most 20). */
    val setupSessions: List<String>
        get() = prefs.getString("setup_sessions", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun addSetupSession(hash: String) {
        val list = (setupSessions - hash + hash).takeLast(20)
        prefs.edit().putString("setup_sessions", list.joinToString("\n")).apply()
    }

    // ---- Seerr (seerr.md) ----

    var seerrUrl: String
        get() = prefs.getString("seerr_url", "") ?: ""
        set(v) = prefs.edit().putString("seerr_url", v.trim().trimEnd('/')).apply()

    /** Seerr's API key: a login like the Plex token, never shown again after it is entered. */
    var seerrKey: String
        get() = prefs.getString("seerr_key", "") ?: ""
        set(v) = prefs.edit().putString("seerr_key", v.trim()).apply()

    /** Name of the Seerr user the key belongs to, shown in Settings. */
    var seerrUserName: String
        get() = prefs.getString("seerr_user_name", "") ?: ""
        set(v) = prefs.edit().putString("seerr_user_name", v).apply()

    /** Language of Seerr's titles, summaries and posters (seerr.md); "" follows the app language. */
    var seerrLanguage: String
        get() = prefs.getString("seerr_language", "") ?: ""
        set(v) = prefs.edit().putString("seerr_language", v).apply()


    /** Seerr user chosen per profile ("" = main account) as id and name; missing: matched by name. */
    var seerrUsers: Map<String, Pair<Int, String>>
        get() = runCatching {
            val o = JSONObject(prefs.getString("seerr_users", "{}"))
            o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { it.getInt("id") to it.getString("name") } }
        }.getOrDefault(emptyMap())
        set(v) = prefs.edit().putString("seerr_users", JSONObject().apply {
            v.forEach { (k, u) -> put(k, JSONObject().put("id", u.first).put("name", u.second)) }
        }.toString()).apply()

    /** Forgets Seerr: address, key, user choices. */
    fun clearSeerr() {
        prefs.edit().remove("seerr_url").remove("seerr_key").remove("seerr_user_name").remove("seerr_users").apply()
    }

    // ---- Plex player (choices in player/PlayerSettings.kt) ----

    /** Subtitle size in percent of the default 21 dp. */
    var subtitleSize: Int
        get() = prefs.getInt("sub_size", 100)
        set(v) = prefs.edit().putInt("sub_size", v).apply()

    var subtitleColor: String
        get() = prefs.getString("sub_color", "white") ?: "white"
        set(v) = prefs.edit().putString("sub_color", v).apply()

    /** "none" (outline only), "half" or "solid" box behind the text. */
    var subtitleBackground: String
        get() = prefs.getString("sub_bg", "none") ?: "none"
        set(v) = prefs.edit().putString("sub_bg", v).apply()

    var subtitlePosition: String
        get() = prefs.getString("sub_pos", "normal") ?: "normal"
        set(v) = prefs.edit().putString("sub_pos", v).apply()

    var skipBackSeconds: Int
        get() = prefs.getInt("skip_back", 10)
        set(v) = prefs.edit().putInt("skip_back", v).apply()

    var skipForwardSeconds: Int
        get() = prefs.getInt("skip_fwd", 30)
        set(v) = prefs.edit().putInt("skip_fwd", v).apply()

    fun addRecent(key: String) {
        recentChannels = (listOf(key) + recentChannels.filter { it != key }).take(20)
    }

    fun changed() {
        _version.value = _version.value + 1
    }

    fun homeChanged() {
        _homeVersion.value = _homeVersion.value + 1
    }
}
