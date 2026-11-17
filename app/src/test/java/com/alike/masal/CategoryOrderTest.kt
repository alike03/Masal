package com.alike.masal

import com.alike.masal.data.CategoryOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** The user's channel category order over the provider's list. */
class CategoryOrderTest {
    private val provider = listOf("News", "Sport", "Film", "Kids")

    @Test
    fun noOrderKeepsTheProviderOrder() = assertEquals(provider, CategoryOrder.apply(provider, emptyList()))

    @Test
    fun orderedFirstThenNewCategoriesInProviderOrder() =
        assertEquals(listOf("Film", "News", "Sport", "Kids"), CategoryOrder.apply(provider, listOf("Film", "News", "Gone")))

    @Test
    fun moveStaysInBounds() {
        assertEquals(listOf("Sport", "News", "Film", "Kids"), CategoryOrder.move(provider, "News", +1))
        assertEquals(provider, CategoryOrder.move(provider, "News", -1))
        assertEquals(provider, CategoryOrder.move(provider, "Missing", +1))
    }

    @Test
    fun groupsFollowTheOrder() =
        assertEquals(listOf("Kids" to 4, "News" to 1, "Sport" to 2, "Film" to 3),
            CategoryOrder.groups(provider.mapIndexed { i, n -> n to i + 1 }, listOf("Kids")))
}
