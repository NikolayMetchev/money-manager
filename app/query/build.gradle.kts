plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    id("moneymanager.pure-importer-convention")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.mokkery)
}

// Read-only queries over the repositories, free of Compose and the database implementation, so the
// desktop app, Android and the native CLI filter and print the same data the same way.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.repository.read)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.serialization.core)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.mokkery.runtime)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.repository.read)

                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("jvmTest") {
            dependencies {
                implementation(libs.mokkery.core)
            }
        }

        getByName("androidHostTest") {
            dependencies {
                implementation(libs.mokkery.core)
            }
        }
    }
}
