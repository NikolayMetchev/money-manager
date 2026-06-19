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

        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.core)
            }
        }
    }
}
