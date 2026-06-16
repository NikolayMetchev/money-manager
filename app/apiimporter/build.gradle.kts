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
                api(projects.utils.rest)

                implementation(libs.kmlogging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.http)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(libs.kotlinx.serialization.json)
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.utils.rest)

                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.http)
                implementation(projects.utils.bigdecimal)
            }
        }

        val androidMain by getting {
            dependencies {
                api(libs.kotlinx.serialization.json)

                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.http)
                implementation(projects.utils.bigdecimal)
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
