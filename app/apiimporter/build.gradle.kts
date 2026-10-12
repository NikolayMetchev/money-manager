plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
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
                api(libs.kotlinx.serialization.json)

                implementation(projects.app.model.rules)
                implementation(projects.utils.bigdecimal)
                implementation(libs.kermit)
                implementation(libs.kermit.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
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
                api(projects.app.tasks)
                api(projects.utils.rest)
                api(libs.kotlinx.serialization.json)

                implementation(projects.app.model.csv)
                implementation(projects.utils.bigdecimal)
                implementation(libs.kermit.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.http)
            }
        }

        getByName("androidMain") {
            dependencies {
                api(projects.app.tasks)
                api(libs.kotlinx.serialization.json)

                implementation(projects.utils.bigdecimal)
                implementation(libs.kermit.core)
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

        getByName("androidHostTest") {
            dependencies {
                implementation(projects.app.model.rules)
                implementation(projects.app.tasks)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(projects.app.model.rules)
                implementation(projects.app.tasks)
            }
        }
    }
}
