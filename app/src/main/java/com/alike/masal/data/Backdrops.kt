package com.alike.masal.data

import okhttp3.OkHttpClient
import org.json.JSONObject

/** A large landscape photo used behind apps. */
data class Backdrop(val url: String, val title: String, val credit: String)

/**
 * Bing's photo of the day archive: the last 8 days, full HD, free to use as
 * wallpaper, no account or key. Only the TV's IP address reaches Bing.
 */
object Backdrops {
    /** Tests point this at a local server. */
    var base = "https://www.bing.com"

    fun load(http: OkHttpClient): List<Backdrop> {
        val body = http.getString("$base/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US")
        val arr = JSONObject(body).getJSONArray("images")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Backdrop(base + it.getString("urlbase") + "_1920x1080.jpg", it.optString("title"), it.optString("copyright"))
        }
    }
}
