import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Convention for a Compose UI **feature** module split out of the old monolithic `:app:ui:core`.
 *
 * Applies the full Compose Multiplatform stack and centralizes the Compose-UI-test wiring that every
 * feature module's test source sets need (kotlin-test, coroutines-test, ktor mock, mokkery, the shared
 * `:test:app:ui` harness, the androidDeviceTest srcDir sharing, headless system props and the disabled
 * `testAndroidHostTest`). A feature module's own build file then only declares its production deps plus
 * `alias(libs.plugins.mokkery)` (the mokkery plugin marker is not on the build-logic classpath, so it
 * cannot be applied here) and, for jvmTest, `compose.desktop.currentOs` (the `compose` accessor is only
 * available in regular build scripts, not in this precompiled convention).
 */
plugins {
    id("moneymanager.compose-multiplatform-convention")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

configure<KotlinMultiplatformExtension> {
    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(lib("kotlinx-coroutines-test"))
                implementation(lib("ktor-client-mock"))
                implementation(lib("mokkery-runtime"))
                implementation(project(":test:app:ui"))
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(lib("androidx-compose-runtime-desktop"))
                implementation(lib("compose-ui-test-desktop"))
                implementation(lib("mokkery-core"))
            }
        }
        getByName("androidDeviceTest") {
            // Cannot use dependsOn(commonTest) due to source set tree restrictions; share via srcDir below.
            dependencies {
                implementation(kotlin("test"))
                implementation(lib("androidx-compose-runtime"))
                implementation(lib("androidx-compose-ui-test"))
                implementation(lib("kotlinx-coroutines-test"))
                implementation(lib("ktor-client-mock"))
                implementation(lib("mokkery-core"))
                implementation(project(":test:app:ui"))
            }
            kotlin.srcDir("src/commonTest/kotlin")
            resources.srcDir("src/commonTest/resources")
        }
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
