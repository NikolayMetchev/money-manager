enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("gradle/build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        val libsVersionsToml = settings.layout.settingsDirectory
            .file("gradle/libs.versions.toml")
            .asFile
            .readText()
        val androidGradlePluginVersion = Regex("""android-gradle-plugin\s*=\s*"([^"]+)"""")
            .find(libsVersionsToml)
            ?.groupValues
            ?.get(1)
            ?: error("android-gradle-plugin version not found in gradle/libs.versions.toml")
        classpath("com.android.tools.build:gradle:$androidGradlePluginVersion")
    }
}

plugins {
    id("com.pablisco.gradle.auto.include") version "1.3"
    id("com.gradle.develocity") version "4.4.1"
    id("com.autonomousapps.build-health") version "3.8.0"

    // Kotlin plugins declared here for classloader compatibility with DAGP
    id("org.jetbrains.kotlin.multiplatform") version "2.3.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.20" apply false
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/terms-of-service"
        termsOfUseAgree = "yes"
        publishing.onlyIf { true }
    }
}

autoInclude {
    ignore(":gradle:build-logic")
}

rootProject.name = "money-manager"
