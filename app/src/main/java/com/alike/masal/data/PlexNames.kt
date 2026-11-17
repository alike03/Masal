package com.alike.masal.data

import com.alike.masal.R

/**
 * Plex sends the names of its rows and default libraries in English, whatever language is asked
 * for. Known ones are shown in the app's language; anything else (own library names, collections,
 * genres, people) stays as Plex sends it.
 */
object PlexNames {
    private val Movies = setOf("movies", "films")
    private val Shows = setOf("tv shows", "tv series", "shows")

    /** A library's name: Plex's default names translated, own names unchanged. */
    fun section(title: String): String = when (title.trim().lowercase()) {
        in Movies -> Language.text(R.string.plex_lib_movies)
        in Shows -> Language.text(R.string.plex_lib_shows)
        else -> title
    }

    /** A row's title from its Plex hub identifier; unknown rows keep Plex's title. */
    fun hub(id: String, title: String): String {
        fun t(res: Int) = Language.text(res)
        // Rows with a name inside the title ("Recently Added in Movies"): only when the title has
        // Plex's English form, otherwise the server already speaks another language.
        fun inner(prefix: String, res: Int, name: (String) -> String = { it }): String =
            if (title.startsWith(prefix) && title.length > prefix.length) Language.text(res, name(title.removePrefix(prefix))) else title
        return when {
            id == "movie" -> t(R.string.plex_search_movies)
            id == "show" -> t(R.string.plex_search_shows)
            id == "episode" -> t(R.string.plex_search_episodes)
            id == "home.continue" || id.matches(Regex("""(movie|tv)\.inprogress.*""")) -> t(R.string.home_continue_watching)
            id == "home.ondeck" -> t(R.string.plex_on_deck)
            id == "home.movies.recent" -> t(R.string.plex_recent_movies)
            id == "home.television.recent" -> t(R.string.plex_recent_tv)
            id == "home.music.recent" -> t(R.string.plex_recent_music)
            id == "home.photos.recent" -> t(R.string.plex_recent_photos)
            id == "home.videos.recent" -> t(R.string.plex_recent_videos)
            id == "home.playlists" -> t(R.string.plex_recent_playlists)
            id.matches(Regex("""(movie|tv)\.recentlyadded.*""")) -> inner("Recently Added in ", R.string.plex_recently_added_in, ::section)
            id.startsWith("movie.recentlyreleased") -> t(R.string.plex_recently_released_movies)
            id.startsWith("movie.genre") -> inner("Top Movies in ", R.string.plex_top_movies_in)
            id.startsWith("movie.by.actor.or.director") -> inner("Top Movies by ", R.string.plex_top_movies_by)
            id.startsWith("movie.topunwatched") -> t(R.string.plex_top_unwatched_movies)
            id.startsWith("movie.recentlyviewed") -> t(R.string.plex_recently_watched_movies)
            id.startsWith("tv.recentlyaired") -> t(R.string.plex_recently_released_episodes)
            id.startsWith("tv.startwatching") -> t(R.string.plex_start_watching)
            id.startsWith("tv.rediscover") -> t(R.string.plex_rediscover)
            id.startsWith("tv.moreingenre") -> inner("More in ", R.string.plex_more_in)
            id.startsWith("tv.toprated") -> t(R.string.plex_top_rated_tv)
            id.startsWith("tv.recentlyviewed") -> t(R.string.plex_recently_watched_episodes)
            else -> title
        }
    }
}
