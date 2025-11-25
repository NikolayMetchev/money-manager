plugins {
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sort.dependencies)
}

dependencies {
    api(libs.androidx.compose.runtime.desktop)
    api(libs.compose.foundation.layout.desktop)

    implementation(libs.compose.desktop.linux.arm64)
    implementation(libs.compose.desktop.linux.x64)
    implementation(libs.compose.desktop.macos.arm64)
    implementation(libs.compose.desktop.macos.x64)
    implementation(libs.compose.foundation.desktop)
    implementation(libs.compose.desktop.windows.x64)
    implementation(libs.compose.material.desktop)
    implementation(libs.compose.ui.desktop)
    implementation(libs.compose.ui.graphics.desktop)
    implementation(libs.compose.ui.text.desktop)
    implementation(libs.compose.ui.unit.desktop)
    implementation(libs.diamondedge.logging)
    implementation(libs.kmlogging)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqldelight.runtime)
    implementation(projects.composeUi)
    implementation(projects.shared)
    implementation(projects.sharedDatabase)
    implementation(projects.sharedDi)

    runtimeOnly(libs.log4j.core)
    runtimeOnly(libs.log4j.slf4j2.impl)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
    }
}

tasks.withType<JavaCompile> {
    targetCompatibility = "24"
}

compose.desktop {
    application {
        mainClass = "com.moneymanager.MainKt"

        // Add required Java modules for the bundled JRE
        jvmArgs += listOf(
            "--add-modules", "java.sql"
        )

        nativeDistributions {
            // Include java.sql module in the custom runtime
            modules("java.sql", "java.naming", "java.management")
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,  // macOS
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,  // Windows
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb   // Linux
            )

            packageName = "MoneyManager"
            packageVersion = "1.0.4" // Increment for each build to test upgrade
            description = "Personal Finance Management Application"
            vendor = "MoneyManager"

            windows {
                menuGroup = "MoneyManager"
                perUserInstall = true
                shortcut = true
                menu = true
                dirChooser = true
                console = true  // Show console window to see error output
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
