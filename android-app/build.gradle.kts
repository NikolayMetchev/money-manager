plugins {
    id("moneymanager.android-application-convention")
}

kotlin {
    sourceSets {
        val androidMain by getting {
            dependencies {
            implementation(kotlin("stdlib"))
            implementation(compose.components.resources)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(libs.androidx.activity)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(projects.composeUi)
            implementation(projects.shared)
            implementation(projects.sharedDatabase)
            implementation(projects.sharedDi)
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
    implementation(kotlin("stdlib"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(projects.composeUi)
    implementation(projects.shared)
    implementation(projects.sharedDatabase)
    implementation(projects.sharedDi)
}