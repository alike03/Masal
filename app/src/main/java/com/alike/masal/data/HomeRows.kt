package com.alike.masal.data

/**
 * A row that can be shown on Home: one of Plex's own Home rows ([id] is Plex's hub id) or a library
 * collection ([id] is "collection:<ratingKey>"). [group] is "Plex" or the collection's library;
 * [defaultOn] means Plex itself shows it on its Home.
 */
data class HomeRowOption(
    val id: String, val title: String, val group: String, val defaultOn: Boolean,
    /** Library type of a collection ("movie", "show"), shown so movie and TV collections can be told apart. */
    val kind: String? = null,
)

object HomeRows {
    const val PlexGroup = "Plex"

    /** Rating key of a promoted collection from its hub id, "custom.collection.<section>.<ratingKey>..." */
    fun collectionKey(hubId: String): String? = CollectionHub.find(hubId)?.groupValues?.get(1)

    private val CollectionHub = Regex("""^custom\.collection\.\d+\.(\d+)""")

    /**
     * All choices: Plex's own rows first, then the collections library by library. A promoted
     * collection is the same choice as the collection itself (on by default).
     */
    fun options(plexHubs: List<PlexHub>, collections: List<Pair<PlexSection, PlexItem>>): List<HomeRowOption> {
        val promoted = plexHubs.mapNotNull { collectionKey(it.id) }.toSet()
        val known = collections.map { it.second.ratingKey }.toSet()
        val plexRows = plexHubs.mapNotNull { h ->
            val key = collectionKey(h.id)
            when {
                key == null -> HomeRowOption(h.id, h.title, PlexGroup, true)
                key !in known -> HomeRowOption("collection:$key", h.title, PlexGroup, true) // e.g. a library we do not list
                else -> null
            }
        }
        val libraryRows = collections.map { (s, c) ->
            HomeRowOption("collection:${c.ratingKey}", c.title, s.title, c.ratingKey in promoted, s.type)
        }
        return (plexRows + libraryRows).distinctBy { it.id }
    }
}
