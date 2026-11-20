package com.alike.masal

import com.alike.masal.data.HomeRows
import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's row order (home.md, "Row order") over the rows present in their default order. */
class HomeOrderTest {
    private val rows = listOf("apps", "deck", "tv", "hub:a", "hub:b", "seerr:4")

    @Test
    fun noOrderKeepsTheDefault() = assertEquals(rows, HomeRows.ordered(rows, emptyList()))

    @Test
    fun movedRowsKeepTheirPlace() {
        val stored = HomeRows.move(rows, emptyList(), "tv", -1).let { HomeRows.move(rows, it, "tv", -1) }
        assertEquals(listOf("tv", "apps", "deck", "hub:a", "hub:b", "seerr:4"), HomeRows.ordered(rows, stored))
        // Out of bounds and unknown rows change nothing.
        assertEquals(HomeRows.ordered(rows, stored), HomeRows.ordered(rows, HomeRows.move(rows, stored, "tv", -1)))
        assertEquals(HomeRows.ordered(rows, stored), HomeRows.ordered(rows, HomeRows.move(rows, stored, "hub:x", 1)))
    }

    @Test
    fun newRowGoesAfterItsDefaultNeighbour() {
        val stored = listOf("tv", "hub:b", "apps", "deck", "hub:a")
        // "hub:c" comes after "hub:b" by default; "seerr:4" after "hub:c".
        val present = listOf("apps", "deck", "tv", "hub:a", "hub:b", "hub:c", "seerr:4")
        assertEquals(listOf("tv", "hub:b", "hub:c", "seerr:4", "apps", "deck", "hub:a"), HomeRows.ordered(present, stored))
        // A new first row goes first.
        assertEquals(listOf("setup2", "tv", "hub:b", "apps", "deck", "hub:a"),
            HomeRows.ordered(listOf("setup2", "apps", "deck", "tv", "hub:a", "hub:b"), stored))
    }

    @Test
    fun rowSwitchedOffKeepsItsPlace() {
        val stored = listOf("tv", "hub:b", "apps", "deck", "hub:a")
        val withoutB = listOf("apps", "deck", "tv", "hub:a")
        // Moving others while "hub:b" is off: it stays between "tv" and "apps".
        val moved = HomeRows.move(withoutB, stored, "hub:a", -1)
        assertEquals(listOf("tv", "apps", "hub:a", "deck"), HomeRows.ordered(withoutB, moved))
        assertEquals(listOf("tv", "hub:b", "apps", "hub:a", "deck"), HomeRows.ordered(withoutB + "hub:b", moved))
    }
}
