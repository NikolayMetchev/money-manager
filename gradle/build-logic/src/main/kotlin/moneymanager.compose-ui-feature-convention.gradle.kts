/**
 * Convention for a Compose UI **feature/library** module split out of the old monolithic `:app:ui:core`.
 *
 * This applies the Compose Multiplatform stack and centralizes the **test configuration** every such
 * module shares — headless Compose Desktop UI tests, the androidDeviceTest source/resource sharing from
 * commonTest, and the disabled `testAndroidHostTest`. It deliberately adds **no dependencies**: the
 * dependency-analysis plugin (`buildHealth`) fails on any unused dependency, so each module declares its
 * own production and test deps (and applies `alias(libs.plugins.mokkery)` where it mocks, plus
 * `compose.desktop.currentOs` in jvmTest where it runs UI tests — the `compose` accessor is unavailable
 * inside this precompiled convention).
 */
plugins {
    id("moneymanager.compose-multiplatform-convention")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Share the commonTest Compose UI tests with the Android device-test variant (cannot use
// dependsOn(commonTest) due to KMP source-set tree restrictions).
extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
    sourceSets.getByName("androidDeviceTest") {
        // kotlin("test") is universally used by the shared tests (and DAGP-excluded), so wire it here;
        // all other deps are declared per-module to keep buildHealth's unused-dependency check happy.
        dependencies {
            implementation(kotlin("test"))
        }
        kotlin.srcDir("src/commonTest/kotlin")
        resources.srcDir("src/commonTest/resources")
    }
}

// Run Compose Desktop UI tests headless (matches the old :app:ui:core configuration).
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    systemProperty("skiko.test.harness", "true")
}

// The shared commonTest Compose UI tests already run as jvmTest (Compose Desktop) and androidDeviceTest
// (managed emulator); skip the JVM host-test run that would need Robolectric/a device.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    enabled = false
}
