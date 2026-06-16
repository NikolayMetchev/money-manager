plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.pure-importer-convention")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
