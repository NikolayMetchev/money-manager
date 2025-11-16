plugins {
    kotlin("multiplatform") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
    id("app.cash.sqldelight") version "2.0.2" apply false
    id("dev.zacsweers.metro") version "0.7.5" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
