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
                api(projects.utils.rest)

                implementation(libs.kmlogging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.http)
            }
        }

        getByName("jvmMain") {
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

        getByName("androidMain") {
            dependencies {
                api(libs.kotlinx.serialization.json)

                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.http)
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
