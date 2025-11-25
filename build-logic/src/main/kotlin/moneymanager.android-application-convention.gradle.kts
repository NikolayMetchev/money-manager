import com.android.build.api.dsl.ApplicationExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("moneymanager.kotlin-convention")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KotlinMultiplatformExtension> {
    androidTarget()

    jvmToolchain(libs.findVersion("jvm-toolchain").get().toString().toInt())
}

configure<ApplicationExtension> {
    compileSdk = libs.findVersion("android-compileSdk").get().toString().toInt()

    defaultConfig {
        minSdk = libs.findVersion("android-minSdk").get().toString().toInt()
        targetSdk = libs.findVersion("android-targetSdk").get().toString().toInt()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }
}

// Override ktlint android setting for Android modules
ktlint {
    android.set(true)
}