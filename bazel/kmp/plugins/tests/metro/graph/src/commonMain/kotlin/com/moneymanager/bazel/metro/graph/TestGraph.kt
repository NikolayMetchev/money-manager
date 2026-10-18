package com.moneymanager.bazel.metro.graph

import com.moneymanager.bazel.metro.scope.TestScope
import dev.zacsweers.metro.DependencyGraph

/** Names no binding container: Metro finds GreetingModule's contribution hint on the compile classpath. */
@DependencyGraph(TestScope::class)
interface TestGraph {
    val greeting: String
}
