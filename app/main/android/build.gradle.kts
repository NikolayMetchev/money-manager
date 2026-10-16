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
}

dependencies {
    implementation(projects.app.cryptodata)
    implementation(projects.app.db.core)
    implementation(projects.app.db.write)
    implementation(projects.app.di.core)
    implementation(projects.app.di.params)
    implementation(projects.app.importfilesource.core)
    implementation(projects.app.importfilesource.di)
    implementation(projects.app.model.core)
    implementation(projects.app.presenter.core)
    implementation(projects.app.remotestorage.core)
    // Native Android Google Drive auth (AndroidGoogleAccessTokenSource) implements the googledrive seam.
    implementation(projects.app.remotestorage.googledrive)
    implementation(projects.app.remotestorage.sync)
    implementation(projects.app.session)
    implementation(projects.app.strategycatalog)
    implementation(projects.app.ui.core)
    implementation(projects.utils.credentialvault)
    implementation(projects.utils.localsettings)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.kermit)
    implementation(libs.kermit.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.play.services.auth)
}
