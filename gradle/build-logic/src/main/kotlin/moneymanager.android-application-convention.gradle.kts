import com.android.build.api.dsl.ApplicationExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

apply(plugin = "com.android.application")
apply(plugin = "moneymanager.kotlin-convention")
apply(plugin = "org.jetbrains.compose")
apply(plugin = "org.jetbrains.kotlin.plugin.compose")

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val jvmTargetVersion = libs.findVersion("jvm-target").get().toString()

configure<KotlinAndroidProjectExtension> {
    jvmToolchain(libs.findVersion("jvm-toolchain").get().toString().toInt())

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
    }
}

configure<ApplicationExtension> {
    compileSdk = libs.findVersion("android-compileSdk").get().toString().toInt()

    defaultConfig {
        minSdk = libs.findVersion("android-minSdk").get().toString().toInt()
        targetSdk = libs.findVersion("android-targetSdk").get().toString().toInt()
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
