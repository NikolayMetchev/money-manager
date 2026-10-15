plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    id("moneymanager.pure-importer-convention")
    // Shared jvmAndroidMain source set: both platforms read the country from java.util.Locale.
    id("moneymanager.jvm-android-shared-convention")
}

// API-connection orchestration free of Compose and the database implementation: create missing connection
// rows from the credential vault, download a credential's new session, import a session, and "download &
// import all". The desktop app, Android and the native CLI run the same code.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.apiimporter)
                api(projects.app.importengineapi)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.repository.read)
                api(projects.app.tasks)
                api(projects.utils.credentialvault)
                api(libs.kotlinx.coroutines.core)
                api(libs.ktor.client.core)

                implementation(projects.utils.rest)
                implementation(libs.kermit)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.apiimporter)
                api(projects.app.importengineapi)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.repository.read)
                api(projects.app.tasks)
                api(projects.utils.credentialvault)

                implementation(libs.kermit.core)
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(libs.kermit.core)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
