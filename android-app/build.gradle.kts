plugins {
    id("moneymanager.android-application-convention")
}

kotlin {
    sourceSets {
        val androidMain by getting {
            dependencies {
                implementation(projects.shared)
                implementation(projects.sharedDatabase)
                implementation(projects.sharedDi)
                implementation(projects.composeUi)

                implementation(libs.androidx.activity)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.compose.runtime)
                implementation(libs.sqldelight.runtime)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)

                implementation(libs.kotlinx.coroutines.core)
                implementation(kotlin("stdlib"))
            }
        }
    }
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
    // Direct dependencies for buildHealth (also declared in androidMain source set)
    implementation(projects.shared)
    implementation(projects.sharedDatabase)
    implementation(projects.sharedDi)
    implementation(projects.composeUi)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(kotlin("stdlib"))
}