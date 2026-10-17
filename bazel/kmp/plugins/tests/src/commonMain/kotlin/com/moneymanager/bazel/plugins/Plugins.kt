package com.moneymanager.bazel.plugins

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import kotlinx.serialization.Serializable
import tech.mappie.api.ObjectMappie

// One use of each compiler plugin (#1001), compiled for the JVM and natively.

@Serializable
data class Payee(val name: String, val accountNumber: Int)

data class PayeeRow(val name: String, val accountNumber: Int)

object PayeeRowMapper : ObjectMappie<PayeeRow, Payee>()

@Composable
fun greeting(name: String): String = remember(name) { "Hello, $name" }

/** A composition target that holds no nodes, so a composition can run with nothing but the runtime. */
object NoNodes : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit

    override fun insertBottomUp(index: Int, instance: Unit) = Unit

    override fun remove(index: Int, count: Int) = Unit

    override fun move(from: Int, to: Int, count: Int) = Unit

    override fun onClear() = Unit
}

@DependencyGraph
interface GreetingGraph {
    val greeting: String

    @Provides
    fun provideGreeting(): String = "Hello from Metro"
}

interface Ledger {
    fun balance(account: String): Long
}
