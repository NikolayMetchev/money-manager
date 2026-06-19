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

                implementation(libs.kmlogging)
                implementation(libs.kotlinx.datetime)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)

                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(projects.utils.bigdecimal)
            }
        }
    }
}
