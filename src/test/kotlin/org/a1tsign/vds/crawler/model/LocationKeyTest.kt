package org.a1tsign.vds.crawler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LocationKeyTest {

    @Test
    fun `match key folds case and treats a missing city or datacenter as empty`() {
        val spelledOut = LocationKey("DE", city = "Frankfurt", datacenterName = "FRA-1").matchKey()
        val otherCase = LocationKey("DE", city = "frankfurt", datacenterName = "fra-1").matchKey()
        val cityOnly = LocationKey("DE", city = "Frankfurt").matchKey()

        assertEquals(spelledOut, otherCase)
        assertEquals(LocationMatchKey("DE", "frankfurt", ""), cityOnly)
        assertNotEquals(spelledOut, cityOnly)
    }
}
