plugins {
    id("moneymanager.android-application-convention")
}

val versionFile = rootDir.resolve("VERSION")

android {
    namespace = "com.moneymanager.android"

    defaultConfig {
        applicationId = "com.moneymanager"
        versionCode = 1
        versionName = "1.0.0"
    }

    sourceSets {
        getByName("main") {
            assets.directories.add("src/main/assets")
        }
    }
}

// Copy VERSION file to Android assets
tasks.register<Copy>("copyVersionToAssets") {
    description = "Copies the project VERSION file into Android assets."
    from(versionFile)
    into("src/main/assets")
    inputs.file(versionFile)
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
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.play.services.auth)
    implementation(projects.app.db.core)
    implementation(projects.app.di.core)
    implementation(projects.app.importengineapi)
    implementation(projects.app.model.core)
    implementation(projects.app.remotestorage.core)
    // Native Android Google Drive auth (AndroidGoogleAccessTokenSource) implements the googledrive seam.
    implementation(projects.app.remotestorage.googledrive)
    implementation(projects.app.remotestorage.sync)
    implementation(projects.app.ui.core)
    implementation(projects.app.ui.foundation)
    implementation(projects.utils.localsettings)
}

// Run release build as part of build to catch release issues early
tasks.named("build") {
    dependsOn("assembleRelease")
}
