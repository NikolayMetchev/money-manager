plugins {
    id("moneymanager.compose-multiplatform-convention")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.metro)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.components.resources)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.runtime)
                implementation(compose.ui)
                implementation(libs.kmlogging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.metro.runtime)
                implementation(projects.app.model.core)
                implementation(projects.sharedDatabase)
                implementation(projects.sharedDi)
            }
        }
        val commonTest by getting {
            dependencies {
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)

                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
        }
        val jvmMain by getting {
            dependencies {
                // Material (v2) for jvm-specific dialogs
                implementation(compose.material)
                implementation(compose.uiTooling)
            }
        }
        val jvmTest by getting {
            dependencies {
                // Skiko native libraries for desktop UI tests
                implementation(compose.desktop.currentOs)
            }
        }
        val androidDeviceTest by getting {
            // Note: Cannot use dependsOn(commonTest) due to source set tree restrictions
            // Tests are shared via kotlin.srcDir() below
            dependencies {
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)

                implementation(kotlin("test"))
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.runner)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.turbine)
            }
            kotlin.srcDir("src/commonTest/kotlin")
        }
    }
}

// Compose-specific ktlint configuration
ktlint {
    // Allow wildcard imports and uppercase function names (standard in Compose)
    disabledRules.set(
        setOf(
            "standard:no-wildcard-imports",
            "standard:function-naming",
        ),
    )
}

// Configure tests to run in headless mode for Compose Desktop
tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
    // Additional properties for Skiko on Windows
    systemProperty("skiko.test.harness", "true")
}
