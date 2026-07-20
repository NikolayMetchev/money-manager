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
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.passthrough)
                api(projects.app.model.repository.read)
                api(projects.utils.rest)

                implementation(libs.kmlogging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.http)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.passthrough)
                api(projects.app.model.repository.read)
                api(projects.utils.rest)
                api(libs.kotlinx.serialization.json)

                implementation(projects.app.model.csv)
                implementation(projects.utils.bigdecimal)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.http)
            }
        }

        getByName("androidMain") {
            dependencies {
                api(libs.kotlinx.serialization.json)

                implementation(projects.utils.bigdecimal)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.http)
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
