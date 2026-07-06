package com.moneymanager.csvimporter

import com.moneymanager.domain.model.AccountId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollapsePassThroughChainTest {
    private fun ids(vararg values: Long) = values.map { AccountId(it) }

    @Test
    fun `a chain with no duplicates is unchanged`() {
        val (nodes, descriptions) = collapsePassThroughChain(ids(1, 2, 3), listOf("a", "b"))!!
        assertEquals(ids(1, 2, 3), nodes)
        assertEquals(listOf("a", "b"), descriptions)
    }

    @Test
    fun `a single-conduit chain whose merchant equals the conduit is dropped`() {
        // nodes = [C1, merchant(==C1)]: collapses to one node -> no real pass-through.
        assertNull(collapsePassThroughChain(ids(1, 1), listOf("spend")))
    }

    @Test
    fun `terminal duplicate drops the merchant leg and its description`() {
        // C1 -> C2 -> merchant(==C2): the C2 -> C2 leg collapses.
        val (nodes, descriptions) = collapsePassThroughChain(ids(1, 2, 2), listOf("leg1", "leg2"))!!
        assertEquals(ids(1, 2), nodes)
        assertEquals(listOf("leg1"), descriptions)
    }

    @Test
    fun `a repeated middle conduit collapses that leg only`() {
        // C1 -> C2 -> C2 -> C3(merchant): the middle C2 -> C2 leg collapses.
        val (nodes, descriptions) = collapsePassThroughChain(ids(1, 2, 2, 3), listOf("a", "b", "c"))!!
        assertEquals(ids(1, 2, 3), nodes)
        assertEquals(listOf("a", "c"), descriptions)
    }

    @Test
    fun `a chain that collapses to a single node is dropped`() {
        // Only one distinct account across the whole chain: no real pass-through.
        assertNull(collapsePassThroughChain(ids(5, 5, 5), listOf("x", "y")))
    }

    @Test
    fun `a non-adjacent repeat is a real movement and is kept`() {
        // C1 -> C2 -> C1: money genuinely moves back; not a zero-length hop.
        val (nodes, descriptions) = collapsePassThroughChain(ids(1, 2, 1), listOf("out", "back"))!!
        assertEquals(ids(1, 2, 1), nodes)
        assertEquals(listOf("out", "back"), descriptions)
    }
}
