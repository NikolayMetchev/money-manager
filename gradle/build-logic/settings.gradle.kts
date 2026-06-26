pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Version is hardcoded (not a catalog alias) because type-safe `libs.plugins.*` accessors only
    // become available *after* this very plugin is applied — a bootstrap chicken-and-egg.
    id("dev.panuszewski.typesafe-conventions") version "0.11.1"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // detekt 2.0.0-alpha.4's gradle plugin depends on
        // org.gradle.experimental:gradle-public-api, which is published only here.
        maven("https://repo.gradle.org/gradle/libs-releases")
    }
    // The `libs` version catalog is auto-imported from the parent build by the
    // typesafe-conventions plugin, so it must not be created here.
}

rootProject.name = "build-logic"
