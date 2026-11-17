package com.alike.masal.data

/**
 * A row that can be shown on Home: one of Plex's own Home rows ([id] is Plex's hub id) or a library
 * collection ([id] is "collection:<ratingKey>"). [group] is "Plex" or the collection's library;
 * [defaultOn] means Plex itself shows it on its Home.
 */
data class HomeRowOption(val id: String, val title: String, val group: String, val defaultOn: Boolean, val profile: String? = null) {
    /** The id without the profile prefix: Plex's hub id, "collection:<ratingKey>" or [HomeRows.Continue]. */
    val rowId get() = if (profile == null) id else id.removePrefix(HomeRows.profilePrefix(profile))
}

object HomeRows {
    const val PlexGroup = "Plex"

    /** A profile's Continue Watching (the main account's is always shown and not a choice). */
    const val Continue = "continue"

    /** Row ids of a profile start with this, so the main account's stored choices stay as they were. */
    fun profilePrefix(profileId: String) = "p:$profileId:"

    /**
     * A profile's choices: its Continue Watching (on), then its Plex Home rows and collections like
     * [options], all off by default since the main account's rows already show the shared ones.
     * Grouped under the profile's name, collections as "name: library".
     */
    fun profileOptions(
        profile: PlexProfile, continueTitle: String, plexHubs: List<PlexHub>, collections: List<Pair<PlexSection, PlexItem>>,
    ): List<HomeRowOption> {
        val prefix = profilePrefix(profile.id)
        val rows = listOf(HomeRowOption(Continue, continueTitle, PlexGroup, true)) + options(plexHubs, collections)
        return rows.map { o ->
            HomeRowOption(
                id = prefix + o.id, title = o.title,
                group = if (o.group == PlexGroup) profile.name else "${profile.name}: ${o.group}",
                defaultOn = o.id == Continue, profile = profile.id,
            )
        }
    }

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
            HomeRowOption("collection:${c.ratingKey}", c.title, s.title, c.ratingKey in promoted)
        }
        return (plexRows + libraryRows).distinctBy { it.id }
    }
}
