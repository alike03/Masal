package com.alike.masal

import androidx.test.core.app.ApplicationProvider
import com.alike.masal.data.IptvSource
import com.alike.masal.data.Language
import com.alike.masal.data.Xtream
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Xtream channel lists are read as a stream; providers mix numbers, strings and nulls. Fictitious data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XtreamParseTest {
    private val server = MockWebServer()

    @Before
    fun init() {
        Language.init(ApplicationProvider.getApplicationContext())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = when {
                    request.path!!.contains("get_live_categories") -> """[
                        {"category_id":"1","category_name":"News","parent_id":0},
                        {"category_id":2,"category_name":"Sport"}]"""
                    request.path!!.contains("get_live_streams") -> """[
                        {"num":1,"name":" Test News ","stream_type":"live","stream_id":101,"stream_icon":"http://example.invalid/n.png",
                         "epg_channel_id":"news.test","category_id":"1","category_ids":[1],"tv_archive":0,"direct_source":"","extra":{"a":[1,2]}},
                        {"name":"Test Sport","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":2},
                        {"name":"Test Misc","stream_id":103,"stream_icon":null,"epg_channel_id":"null","category_id":"99"}]"""
                    else -> "[]"
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    @Test
    fun streamsAreReadWithTheirCategories() {
        val src = IptvSource("s1", "Test", "xtream", server = server.url("/").toString(), user = "u", pass = "p")
        val list = Xtream.channels(OkHttpClient(), src)
        assertEquals(listOf("Test News", "Test Sport", "Test Misc"), list.map { it.name })
        assertEquals(listOf("News", "Sport", "Other"), list.map { it.group })
        assertEquals("news.test", list[0].epgId)
        assertNull(list[1].epgId)
        assertNull(list[2].epgId)
        assertEquals("http://example.invalid/n.png", list[0].logo)
        assertNull(list[1].logo)
        assertTrue(list[1].url.endsWith("/live/u/p/102.ts"))
        assertEquals("s1|Test News", list[0].key)
    }
}
