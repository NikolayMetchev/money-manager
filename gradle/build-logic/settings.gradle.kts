pluginManagement {
    repositories {
        google {
            // Google's Maven only hosts Android/Google artifacts; keep every other lookup off it.
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
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
        google {
            // Google's Maven only hosts Android/Google artifacts; keep every other lookup off it.
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // detekt 2.0.0-alpha.4's gradle plugin depends on
        // org.gradle.experimental:gradle-public-api, which is published only here.
        exclusiveContent {
            forRepository { maven("https://repo.gradle.org/gradle/libs-releases") }
            filter { includeGroup("org.gradle.experimental") }
        }
    }
    // The `libs` version catalog is auto-imported from the parent build by the
    // typesafe-conventions plugin, so it must not be created here.
}

rootProject.name = "build-logic"
