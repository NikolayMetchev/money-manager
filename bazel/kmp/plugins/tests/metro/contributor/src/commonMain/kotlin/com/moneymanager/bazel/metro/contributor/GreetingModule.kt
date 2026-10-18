package com.moneymanager.bazel.metro.contributor

import com.moneymanager.bazel.metro.scope.TestScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

private const val GREETING = "contributed from another module"

/** Contributed from another module, as every feature's DI module is to AppComponent. */
@ContributesTo(TestScope::class)
@BindingContainer
object GreetingModule {
    @Provides
    fun greeting(): String = GREETING
}
