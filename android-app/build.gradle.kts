plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget()

    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

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
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.moneymanager"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
