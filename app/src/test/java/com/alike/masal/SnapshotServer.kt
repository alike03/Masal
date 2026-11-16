package com.alike.masal

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Answers like Plex, the IPTV provider and Bing, from snapshot/snapshot.zip. */
class SnapshotServer(file: File) {
    private val zip = ZipFile(file)
    val server = MockWebServer()
    val base get() = server.url("").toString().trimEnd('/')

    fun entry(name: String): ByteArray? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

    fun start() {
        val responses = JSONObject(String(entry("responses.json")!!))
        val images = JSONObject(String(entry("images.json")!!))
        val bing = entry("bing.json")?.let { JSONArray(String(it)) } ?: JSONArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                fun bytes(b: ByteArray?, type: String) =
                    if (b == null) MockResponse().setResponseCode(404)
                    else MockResponse().setHeader("Content-Type", type).setBody(Buffer().write(b))
                val key = com.alike.masal.debug.key(url)
                return when {
                    path == "/photo/:/transcode" -> images.optString(url.queryParameter("url") ?: "").takeIf { it.isNotBlank() }
                        .let { f -> bytes(f?.let { entry("images/$it") }, if (f?.endsWith(".png") == true) "image/png" else "image/jpeg") }
                    path == "/HPImageArchive.aspx" -> MockResponse().setBody(JSONObject().put("images", JSONArray().apply {
                        for (i in 0 until bing.length()) put(JSONObject().put("urlbase", "/bing/$i")
                            .put("title", bing.getJSONObject(i).optString("title")).put("copyright", bing.getJSONObject(i).optString("credit")))
                    }).toString())
                    path.startsWith("/bing/") -> bytes(entry("bing/" + path.removePrefix("/bing/").substringBefore('_') + ".jpg"), "image/jpeg")
                    path.startsWith("/logos/") -> bytes(entry(path.removePrefix("/")), "image/png")
                    path == "/xmltv.php" -> bytes(entry("epg.xml"), "application/xml")
                    path == "/player_api.php" -> responses.optString("xtream/" + url.queryParameter("action")).takeIf { it.isNotBlank() }
                        ?.let { MockResponse().setBody(it.replace("MASAL_MOCK", base)) }
                        ?: MockResponse().setResponseCode(404)
                    responses.has(key) -> MockResponse().setHeader("Content-Type", "application/json").setBody(responses.getString(key))
                    responses.has(path) -> MockResponse().setHeader("Content-Type", "application/json").setBody(responses.getString(path))
                    else -> { println("MOCK 404 $key"); MockResponse().setResponseCode(404) }
                }
            }
        }
        server.start()
    }

    fun stop() { runCatching { server.shutdown() }; runCatching { zip.close() } }
}
