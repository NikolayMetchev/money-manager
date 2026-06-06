enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

val projectVersion = System.getProperty("version")
    ?: providers.gradleProperty("version").orNull
    ?: layout.settingsDirectory.file("VERSION").asFile.readText().trim()

gradle.beforeProject {
    version = projectVersion
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

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
    id("com.gradle.develocity") version "4.4.2"
    id("com.autonomousapps.build-health") version "3.14.1"

    // Kotlin plugins declared here for classloader compatibility with DAGP
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/terms-of-service"
        termsOfUseAgree = "yes"
        publishing.onlyIf { true }
    }
}

rootProject.name = "money-manager"

rootDir.walkTopDown()
    .mapNotNull { file ->
        file.takeIf { it.name == "build.gradle.kts" }
            ?.parentFile
            ?.takeUnless { it == rootDir }
            ?.takeUnless { moduleDir ->
                moduleDir.toRelativeString(rootDir).replace('\\', '/') == "gradle/build-logic"
            }
    }
    .forEach { moduleDir ->
        include(moduleDir.relativeTo(rootDir).path.replace('/', ':').replace('\\', ':'))
    }
