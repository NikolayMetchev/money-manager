package com.moneymanager.bazel.metro.graph

import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertEquals

class TestGraphTest {
    @Test
    fun mergesTheOtherModulesContribution() = assertEquals("contributed from another module", createGraph<TestGraph>().greeting)
}
