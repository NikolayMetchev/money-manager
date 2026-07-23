plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.core)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.core)
            }
        }
    }
}
