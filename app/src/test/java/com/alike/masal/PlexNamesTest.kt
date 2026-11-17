package com.alike.masal

import androidx.test.core.app.ApplicationProvider
import com.alike.masal.data.Language
import com.alike.masal.data.PlexNames
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plex's English row and library names in the app's language (device in German); own names stay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de")
class PlexNamesTest {
    @Before
    fun init() = Language.init(ApplicationProvider.getApplicationContext())

    @Test
    fun libraries() {
        assertEquals("Filme", PlexNames.section("Movies"))
        assertEquals("Serien", PlexNames.section("TV Shows"))
        assertEquals("Kinderfilme", PlexNames.section("Kinderfilme"))
    }

    @Test
    fun rows() {
        assertEquals("Weiterschauen", PlexNames.hub("movie.inprogress.1", "Continue Watching"))
        assertEquals("Neu hinzugefügt: Filme", PlexNames.hub("movie.recentlyadded.1", "Recently Added in Movies"))
        assertEquals("Neue Serien", PlexNames.hub("home.television.recent", "Recently Added TV"))
        assertEquals("Mehr aus Comedy", PlexNames.hub("tv.moreingenre.2", "More in Comedy"))
        // Collections and unknown rows keep Plex's title; so does a title the server already translated.
        assertEquals("Marvel Cinematic Universe", PlexNames.hub("custom.collection.1.10099.10099", "Marvel Cinematic Universe"))
        assertEquals("Kürzlich hinzugefügt in Filme", PlexNames.hub("movie.recentlyadded.1", "Kürzlich hinzugefügt in Filme"))
    }
}
