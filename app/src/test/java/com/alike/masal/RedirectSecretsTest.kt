package com.alike.masal

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Plex token and the Seerr key are not sent on to another host by a redirect (plain JVM test). */
class RedirectSecretsTest {
    private val http = OkHttpClient.Builder().addNetworkInterceptor(::dropSecretsOnRedirect).build()

    private fun get(url: String) = http.newCall(
        Request.Builder().url(url).header("X-Plex-Token", "secret-token").header("X-Api-Key", "secret-key")
            .header("X-API-User", "7").header("Accept", "application/json").build()
    ).execute().close()

    @Test
    fun otherHostGetsNoSecrets() {
        MockWebServer().use { target ->
            MockWebServer().use { origin ->
                target.enqueue(MockResponse().setBody("ok"))
                // Same server, reached by another name: a different host for OkHttp.
                val elsewhere = target.url("/landing").newBuilder().host("localhost").build()
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere))
                get(origin.url("/start").newBuilder().host("127.0.0.1").build().toString())
                val first = origin.takeRequest()
                assertEquals("secret-token", first.getHeader("X-Plex-Token"))
                val second = target.takeRequest()
                assertEquals("/landing", second.path)
                assertNull(second.getHeader("X-Plex-Token"))
                assertNull(second.getHeader("X-Api-Key"))
                assertNull(second.getHeader("X-API-User"))
                assertEquals("other headers stay", "application/json", second.getHeader("Accept"))
            }
        }
    }

    @Test
    fun sameHostKeepsSecrets() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/moved"))
            server.enqueue(MockResponse().setBody("ok"))
            get(server.url("/start").toString())
            server.takeRequest()
            val moved = server.takeRequest()
            assertEquals("/moved", moved.path)
            assertEquals("secret-token", moved.getHeader("X-Plex-Token"))
            assertEquals("secret-key", moved.getHeader("X-Api-Key"))
        }
    }
}
