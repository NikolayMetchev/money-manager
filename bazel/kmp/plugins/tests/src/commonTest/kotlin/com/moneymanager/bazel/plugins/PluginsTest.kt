package com.moneymanager.bazel.plugins

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import dev.zacsweers.metro.createGraph
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginsTest {
    @Test
    fun serializationGeneratesASerializer() =
        assertEquals(listOf("name", "accountNumber"), serializer<Payee>().descriptor.elementNames.toList())

    @Test
    fun mappieGeneratesTheMapping() = assertEquals(Payee("Rent", 7), PayeeRowMapper.map(PayeeRow("Rent", 7)))

    @Test
    fun composeRunsAComposition() =
        runTest {
            withContext(BroadcastFrameClock()) {
                val recomposer = Recomposer(coroutineContext)
                val composition = Composition(NoNodes, recomposer)
                var result = ""
                composition.setContent { result = greeting("Bazel") }
                assertEquals("Hello, Bazel", result)
                composition.dispose()
                recomposer.cancel()
            }
        }

    @Test
    fun metroBuildsTheGraph() = assertEquals("Hello from Metro", createGraph<GreetingGraph>().greeting)

    @Test
    fun mokkeryMocksAnInterface() {
        val ledger = mock<Ledger> { every { balance("Cash") } returns 42L }
        assertEquals(42L, ledger.balance("Cash"))
    }
}
