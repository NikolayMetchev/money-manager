pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
    versionCatalogs {
        create("libs") {
            from(files("../libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
