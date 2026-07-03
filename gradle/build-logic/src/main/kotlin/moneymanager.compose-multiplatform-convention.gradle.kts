plugins {
    alias(conventions.plugins.moneymanager.android.convention)
    alias(conventions.plugins.moneymanager.kotlin.multiplatform.convention)
    alias(libs.plugins.compose)
    alias(libs.plugins.test.retry)
}

// Workaround: Compose Multiplatform 1.10.0 doesn't configure outputDirectory for
// the AndroidDeviceTest variant's CopyResourcesToAndroidAssetsTask with AGP 9.0.
// Disable this task since compose resources aren't needed for device tests.
tasks.matching {
    it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets"
}.configureEach {
    enabled = false
}

// Compose Desktop (jvmTest) UI tests occasionally hit ComposeTimeoutException purely from CPU
// starvation when the whole `build` runs them alongside every other module's compile/test work
// (they pass in isolation). Retry a failed test a couple of times so a load-induced flake doesn't
// fail the build; a test that only ever fails still fails after its retries are exhausted.
tasks.withType<Test>().configureEach {
    // Keep Compose Desktop tests in a single fork, overriding the parallel-forks default from
    // moneymanager.kotlin-convention (this plugin applies after it, so this action runs last):
    // parallel Skiko/AWT test JVMs make the CPU-starvation flakes above much more likely.
    maxParallelForks = 1
    retry {
        maxRetries.set(2)
        // A test that passes on retry is treated as passed (the point of retrying flakes).
        failOnPassedAfterRetry.set(false)
    }
}