plugins {
    alias(libs.plugins.kotlin.serialization)
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.accountmapping)
                api(projects.app.model.core)
                api(projects.app.model.qif)
            }
        }

        getByName("androidMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.accountmapping)
                api(projects.app.model.core)
                api(libs.kotlinx.serialization.core)
            }
        }
    }
}
