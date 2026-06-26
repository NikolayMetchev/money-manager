import com.android.build.api.dsl.ApplicationExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    alias(libs.plugins.android.application)
    alias(conventions.plugins.moneymanager.kotlin.convention)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

val jvmTargetVersion = libs.versions.jvm.target.get()

configure<KotlinAndroidProjectExtension> {
    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
    }
}

configure<ApplicationExtension> {
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
        targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // API 37 is currently beta, so stay on the latest stable SDK while keeping other lint warnings fatal.
        disable += "GradleDependency"
        disable += "OldTargetApi"
    }
}

// Disable Compose mapping file generation to work around Java 25 compatibility issue
// (ASM version used by the task doesn't support class file major version 69)
configure<ComposeCompilerGradlePluginExtension> {
    includeComposeMappingFile.set(false)
}

// Override ktlint android setting for Android modules
configure<KtlintExtension> {
    android.set(true)
}
