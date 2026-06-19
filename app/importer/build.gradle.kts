plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.pure-importer-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.core)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
