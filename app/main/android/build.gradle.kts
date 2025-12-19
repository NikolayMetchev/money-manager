plugins {
    id("moneymanager.android-application-convention")
}

android {
    namespace = "com.moneymanager.android"

    defaultConfig {
        applicationId = "com.moneymanager"
        versionCode = 1
        versionName = "1.0.0"
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

// Copy VERSION file to Android assets
tasks.register<Copy>("copyVersionToAssets") {
    from(rootProject.file("VERSION"))
    into("src/main/assets")
    inputs.file(rootProject.file("VERSION"))
    outputs.file("src/main/assets/VERSION")
}

tasks.named("preBuild") {
    dependsOn("copyVersionToAssets")
}

// Ensure explodeAssetSourceDebug runs after copyVersionToAssets
tasks.matching { it.name.startsWith("explodeAssetSource") }.configureEach {
    mustRunAfter("copyVersionToAssets")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(compose.components.resources)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime)
    implementation(projects.app.db.core)
    implementation(projects.app.di.core)
    implementation(projects.app.ui.core)
}

// Run release build as part of build to catch release issues early
tasks.named("build") {
    dependsOn("assembleRelease")
}
