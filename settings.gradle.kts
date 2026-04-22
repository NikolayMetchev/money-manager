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
        classpath("com.android.tools.build:gradle:9.2.0")
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
