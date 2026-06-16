plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.pure-importer-convention")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.app.csvimporter)
                api(projects.app.importengineapi)
                api(projects.app.model.core)

                implementation(libs.kmlogging)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(projects.app.csvimporter)
                api(projects.app.importengineapi)
                api(projects.app.model.core)

                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
