import com.autonomousapps.DependencyAnalysisSubExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("dev.detekt")
    id("com.autonomousapps.dependency-analysis")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KotlinMultiplatformExtension> {
    jvm()

    jvmToolchain(libs.findVersion("jvm-toolchain").get().toString().toInt())

    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

repositories {
    google()
    mavenCentral()
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.findVersion("jvm-target").get().toString()))
    }
}

detekt {
    config.setFrom(rootProject.file("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}

// Make check task depend on detekt to run it as part of the build
tasks {
    check {
        dependsOn(detekt)
    }
    build {
        dependsOn(buildHealth)
    }
}

// Dependency analysis configuration
configure<DependencyAnalysisSubExtension> {
    issues {
        onAny {
            severity("fail")

            // Ignore warnings for platform-specific Skiko natives
            // We bundle all platforms to create cross-platform distributions
            exclude("org.jetbrains.compose.desktop:desktop-jvm-windows-x64")
            exclude("org.jetbrains.compose.desktop:desktop-jvm-macos-x64")
            exclude("org.jetbrains.compose.desktop:desktop-jvm-macos-arm64")
            exclude("org.jetbrains.compose.desktop:desktop-jvm-linux-x64")
            exclude("org.jetbrains.compose.desktop:desktop-jvm-linux-arm64")
        }
    }
}
