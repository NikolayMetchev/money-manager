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
        // SQLDelight 2.4.0-SNAPSHOT: needed for Gradle Isolated Projects support (sqldelight/sqldelight#6259),
        // which the latest 2.3.2 release lacks. Scoped to app.cash.sqldelight + snapshots only so it doesn't
        // affect resolution of anything else. Remove once 2.4.0 is released.
        maven {
            name = "centralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroup("app.cash.sqldelight") }
        }
    }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        // See note in dependencyResolutionManagement — required for the SQLDelight Gradle plugin snapshot.
        maven {
            name = "centralSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent { snapshotsOnly() }
            content { includeGroup("app.cash.sqldelight") }
        }
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
    id("com.gradle.develocity") version "4.5.0"
    id("com.autonomousapps.build-health") version "3.15.0"

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
    // Skip hidden directories (.git, .gradle, .idea, tooling dirs like .claude/worktrees that may hold
    // a checked-out copy of the repo) so their build.gradle.kts files aren't registered as modules.
    .onEnter { dir -> !dir.name.startsWith(".") }
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
