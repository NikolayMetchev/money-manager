plugins {
    id("moneymanager.kotlin-convention")
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.androidx.compose.runtime.desktop)
    api(libs.compose.ui.desktop)
    implementation(libs.compose.ui.graphics.desktop)
    implementation(libs.compose.ui.unit.desktop)
    implementation(libs.diamondedge.logging)
    implementation(libs.kmlogging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(projects.app.ui.core)
    implementation(projects.sharedDatabase)
    implementation(projects.sharedDi)

    runtimeOnly(compose.desktop.currentOs)
    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)
}

// Copy VERSION file to resources
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("VERSION")) {
        into(".")
    }
}

compose.desktop {
    application {
        mainClass = "com.moneymanager.MainKt"

        // Add required Java modules for the bundled JRE
        jvmArgs +=
            listOf(
                "--add-modules", "java.sql",
            )

        nativeDistributions {
            // Include java.sql module in the custom runtime
            modules("java.sql", "java.naming", "java.management")
            targetFormats(
                // macOS
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                // Windows
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                // Linux
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )

            packageName = "MoneyManager"
            packageVersion = project.version.toString()
            description = "Personal Finance Management Application"
            vendor = "MoneyManager"

            windows {
                menuGroup = "MoneyManager"
                perUserInstall = true
                shortcut = true
                menu = true
                dirChooser = true
                console = true // Show console window to see error output
                // IMPORTANT: Keep this UUID constant across versions to allow upgrades/reinstalls
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }

            macOS {
                bundleID = "com.moneymanager.app"
            }
        }
    }
}

// Handle duplicate JARs in distribution tasks
tasks.withType<Tar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Ignore false positives from dependency-analysis plugin
// These dependencies ARE used directly via imports in Main.kt
dependencyAnalysis {
    issues {
        onUnusedDependencies {
            exclude(
                ":app:ui:core",
                ":shared-database",
                ":shared-di",
            )
        }
    }
}
