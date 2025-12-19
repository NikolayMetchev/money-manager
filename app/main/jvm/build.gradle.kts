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
    implementation(projects.app.db.core)
    implementation(projects.app.di.core)
    implementation(projects.app.ui.core)

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

        buildTypes.release {
            proguard {
                isEnabled.set(true)
                version.set(libs.versions.proguard.get().toString())
                configurationFiles.from(project.file("proguard-rules.pro"))
            }
        }

        // Add required Java modules for the bundled JRE
        jvmArgs +=
            listOf(
                "--add-modules", "java.sql",
            )

        nativeDistributions {
            // Include java.sql module in the custom runtime
            modules("java.sql")
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

// Run release packaging as part of build to catch ProGuard issues early
tasks.named("build") {
    dependsOn("packageReleaseDistributionForCurrentOS")
}
