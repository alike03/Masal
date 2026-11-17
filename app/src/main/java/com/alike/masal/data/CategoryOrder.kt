package com.alike.masal.data

/** The user's order of channel categories. */
object CategoryOrder {
    /** [names] (provider order) sorted by [order]: listed ones first in that order, the rest after, as the provider lists them. */
    fun apply(names: List<String>, order: List<String>): List<String> {
        if (order.isEmpty()) return names
        val present = names.toHashSet()
        val first = order.filter { it in present }.distinct()
        val placed = first.toHashSet()
        return first + names.filter { it !in placed }
    }

    /** Sorts category groups (name to channels) by [order]. */
    fun <T> groups(groups: List<Pair<String, T>>, order: List<String>): List<Pair<String, T>> {
        if (order.isEmpty()) return groups
        val byName = groups.toMap()
        return apply(groups.map { it.first }, order).map { it to byName.getValue(it) }
    }

    /** The full order after moving [name] by [delta] places within [current] (the order as shown). */
    fun move(current: List<String>, name: String, delta: Int): List<String> {
        val list = current.toMutableList()
        val i = list.indexOf(name)
        val j = i + delta
        if (i < 0 || j !in list.indices) return current
        list.removeAt(i); list.add(j, name)
        return list
    }
}
