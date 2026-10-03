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
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Included at the top level (not inside pluginManagement) because the typesafe-conventions plugin
// applied in build-logic requires the included build to be aware of the build hierarchy, which an
// early-evaluated pluginManagement { includeBuild(...) } is not.
includeBuild("gradle/build-logic")

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

        // Workaround for DAGP not yet reading Kotlin 2.4 metadata:
        // https://github.com/autonomousapps/dependency-analysis-gradle-plugin/issues/1661
        val kotlinVersion = Regex("""\nkotlin\s*=\s*"([^"]+)"""")
            .find(libsVersionsToml)
            ?.groupValues
            ?.get(1)
            ?: error("kotlin version not found in gradle/libs.versions.toml")
        classpath("org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion")
    }
}

plugins {
    id("com.gradle.develocity") version "4.5.1"
    id("com.autonomousapps.build-health") version "3.19.2"

    // Kotlin plugins declared here for classloader compatibility with DAGP
    id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/terms-of-service"
        termsOfUseAgree = "yes"
        publishing.onlyIf { true }
    }
}

rootProject.name = "money-manager"

// The release smoke test has no variants without -PreleaseSmokeTest=true, and Android Studio's sync
// fails on a variantless Android module, so leave it out of the build entirely unless asked for.
val releaseSmokeTest = providers.gradleProperty("releaseSmokeTest").map(String::toBoolean).getOrElse(false)

rootDir.walkTopDown()
    // Skip hidden directories (.git, .gradle, .idea, tooling dirs like .claude/worktrees that may hold
    // a checked-out copy of the repo) so their build.gradle.kts files aren't registered as modules, and
    // build output directories, which hold no modules but are by far the largest trees to walk.
    .onEnter { dir -> !dir.name.startsWith(".") && dir.name != "build" }
    .mapNotNull { file ->
        file.takeIf { it.name == "build.gradle.kts" }
            ?.parentFile
            ?.takeUnless { it == rootDir }
            ?.takeUnless { moduleDir ->
                moduleDir.toRelativeString(rootDir).replace('\\', '/') == "gradle/build-logic"
            }?.takeUnless { moduleDir ->
                !releaseSmokeTest &&
                    moduleDir.toRelativeString(rootDir).replace('\\', '/') == "app/main/android-smoketest"
            }
    }
    .forEach { moduleDir ->
        include(moduleDir.relativeTo(rootDir).path.replace('/', ':').replace('\\', ':'))
    }
