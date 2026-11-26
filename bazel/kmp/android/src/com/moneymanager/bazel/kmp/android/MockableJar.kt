package com.moneymanager.bazel.kmp.android

import com.android.builder.testing.MockableJarGenerator
import java.io.File

/**
 * `MockableJar <android.jar> <out>`: AGP's mockable android.jar, the one its host tests run on. With
 * `isReturnDefaultValues = true` (the Android convention's), SDK methods return 0, false or null instead of throwing
 * "Method … not mocked.", so code that logs through `android.util.Log` runs in a host test.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: MockableJar <android.jar> <out>" }
    MockableJarGenerator(true).createMockableJar(File(args[0]), File(args[1]))
}
