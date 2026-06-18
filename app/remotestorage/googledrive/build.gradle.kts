plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// Google Drive remote-storage backend. The Drive Java SDK (and its loopback OAuth flow) is JVM-only,
// so the concrete provider lives in jvmMain. Android and iOS Drive support are documented follow-ups
// (see README.md); until they land, only the JVM build offers a Google Drive backend and the other
// platforms fall back to the local/synced-folder backend.
kotlin {
    sourceSets {
        jvmMain {
            dependencies {
                api(projects.app.remotestorage.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.google.api.client)
                implementation(libs.google.api.services.drive)
                implementation(libs.google.http.client)
                implementation(libs.google.http.client.gson)
                implementation(libs.google.oauth.client)
                implementation(libs.google.oauth.client.java6)
                implementation(libs.google.oauth.client.jetty)
                implementation(libs.kmlogging)
                implementation(libs.diamondedge.logging)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
