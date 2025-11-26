plugins {
    id("moneymanager.kotlin-multiplatform-convention")
}

kotlin {
    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.sqldelight.runtime)
                implementation(projects.sharedDatabase)
            }
        }
    }
}
