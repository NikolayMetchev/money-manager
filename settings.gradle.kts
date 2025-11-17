enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.pablisco.gradle.auto.include") version "1.3"
}

autoInclude {
    ignore(":build-logic")
}

rootProject.name = "money-manager"
