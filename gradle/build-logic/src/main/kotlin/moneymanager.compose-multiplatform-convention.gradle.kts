plugins {
    alias(conventions.plugins.moneymanager.android.convention)
    alias(conventions.plugins.moneymanager.kotlin.multiplatform.convention)
    alias(libs.plugins.compose)
}

// Workaround: Compose Multiplatform 1.10.0 doesn't configure outputDirectory for
// the AndroidDeviceTest variant's CopyResourcesToAndroidAssetsTask with AGP 9.0.
// Disable this task since compose resources aren't needed for device tests.
tasks.matching {
    it.name == "copyAndroidDeviceTestComposeResourcesToAndroidAssets"
}.configureEach {
    enabled = false
}