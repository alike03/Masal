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
    const val SeerrGroup = "Seerr"

    /** Home row ids that are not Plex hubs (home.md, "Row order"); hubs are "hub:<id>", Seerr rows "seerr:<id>". */
    const val AppsRow = "apps"
    const val DeckRow = "deck"
    const val LiveRow = "tv"

    /** Seerr's Discover sliders as choices (seerr.md): all off by default, named by [name] when Seerr gives no title. */
    fun seerrOptions(sliders: List<SeerrSlider>, name: (SeerrSlider) -> String): List<HomeRowOption> =
        sliders.map { HomeRowOption(it.rowId, name(it), SeerrGroup, false) }

    /**
     * The stored order with every row of [present] (given in the default order) in it: a row not
     * stored yet goes right after the row before it in the default order that is stored, or first.
     * Stored rows that are not present (switched off for now) keep their place.
     */
    fun merged(present: List<String>, stored: List<String>): List<String> {
        val out = stored.distinct().toMutableList()
        present.forEachIndexed { i, id ->
            if (id in out) return@forEachIndexed
            val before = (i - 1 downTo 0).map { present[it] }.firstOrNull { it in out }
            out.add(if (before == null) 0 else out.indexOf(before) + 1, id)
        }
        return out
    }

    /** [present] rows in the user's order. */
    fun ordered(present: List<String>, stored: List<String>): List<String> {
        val set = present.toHashSet()
        return merged(present, stored).filter { it in set }
    }

    /**
     * The order to store after moving [id] by [delta] places among the [present] rows: it swaps with
     * its visible neighbour, rows switched off stay where they are.
     */
    fun move(present: List<String>, stored: List<String>, id: String, delta: Int): List<String> {
        val all = merged(present, stored).toMutableList()
        val set = present.toHashSet()
        val visible = all.filter { it in set }
        val from = visible.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in visible.indices) return all
        val a = all.indexOf(id); val b = all.indexOf(visible[to])
        all[a] = visible[to]; all[b] = id
        return all
    }

    /** A profile's Continue Watching (the main account's is always shown and not a choice). */
    const val Continue = "continue"

    /** Row ids of a profile start with this, so the main account's stored choices stay as they were. */
    fun profilePrefix(profileId: String) = "p:$profileId:"

    /**
     * A profile's choices: its Continue Watching (on), then its Plex Home rows and collections like
     * [options], all off by default since the main account's rows already show the shared ones.
     * Grouped like the main account's (Plex, then each library); [HomeRowOption.profile] tells them apart.
     */
    fun profileOptions(
        profile: PlexProfile, continueTitle: String, plexHubs: List<PlexHub>, collections: List<Pair<PlexSection, PlexItem>>,
    ): List<HomeRowOption> {
        val prefix = profilePrefix(profile.id)
        val rows = listOf(HomeRowOption(Continue, continueTitle, PlexGroup, true)) + options(plexHubs, collections)
        return rows.map { o ->
            HomeRowOption(
                id = prefix + o.id, title = o.title,
                group = o.group,
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
