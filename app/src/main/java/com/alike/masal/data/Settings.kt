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
    private val prefs = context.getSharedPreferences("masal", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    /** Bumped whenever sources or Plex settings change, so data gets reloaded. */
    val version: StateFlow<Int> = _version

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

    /** IPTV category shown as a row on Home (matched without accents and provider prefix); empty hides the row. */
    var homeLiveGroup: String
        get() = prefs.getString("home_live_group", "") ?: ""
        set(v) = prefs.edit().putString("home_live_group", v.trim()).apply()

    /** Title of that Home row. */
    var homeLiveTitle: String
        get() = prefs.getString("home_live_title", "")?.ifBlank { null } ?: "Live TV"
        set(v) = prefs.edit().putString("home_live_title", v.trim()).apply()

    fun addRecent(key: String) {
        recentChannels = (listOf(key) + recentChannels.filter { it != key }).take(20)
    }

    fun changed() {
        _version.value = _version.value + 1
    }
}
