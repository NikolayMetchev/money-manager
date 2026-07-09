plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.pure-importer-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                api(projects.utils.bigdecimal)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.core)
                api(projects.utils.bigdecimal)
            }
        }
    }
}
