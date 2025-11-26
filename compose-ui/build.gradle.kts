plugins {
    id("moneymanager.android-convention")
    alias(libs.plugins.compose)
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
                implementation(projects.shared)
                implementation(projects.sharedDatabase)
                implementation(projects.sharedDi)
            }
        }
        val jvmMain by getting {
            dependencies {
                // Material (v2) for jvm-specific dialogs
                implementation(compose.material)
                implementation(compose.uiTooling)
            }
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
