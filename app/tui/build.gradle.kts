plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    alias(libs.plugins.compose.compiler)
}

// The full-screen terminal UI (#918) on Mosaic: the shell (tabs, key bindings, help, prompts, database choice)
// and its screens. `mm tui` runs it from the CLI binary, which supplies how databases open and close, so
// this module knows nothing about command-line options.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.session)
                api(projects.app.terminal)
                api(libs.kotlinx.coroutines.core)
                api(libs.mosaic.runtime)

                implementation(projects.app.presenter.accounts)
                implementation(projects.app.presenter.core)
                implementation(projects.app.presenter.imports)
                implementation(projects.app.presenter.transactions)
                implementation(projects.app.query)
                implementation(projects.utils.files)
                implementation(libs.kermit)
                implementation(libs.mosaic.terminal)
                implementation(libs.mosaic.tty)
                implementation(libs.mosaic.tty.terminal)
            }
        }

        // Common code reaches these through the api deps above, which is all a native compile needs. buildHealth only
        // reads JVM bytecode, and wants the ones it sees used declared here.
        jvmMain {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.tasks)
                api(projects.utils.credentialvault)
                api(libs.androidx.compose.runtime.desktop)

                implementation(projects.app.db.di)
                implementation(projects.app.model.repository.read)
                implementation(projects.utils.bigdecimal)
                implementation(libs.kermit.core)
                implementation(libs.kotlinx.datetime)
            }
        }

        commonTest {
            dependencies {
                implementation(projects.app.importengineapi)
                implementation(projects.app.importfilesource.core)
                implementation(projects.app.importfilesource.localfolder)
                implementation(projects.app.model.importdirectory)
                implementation(projects.test.app.db)
                implementation(projects.test.utils.credentialvault)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.io.core)
                implementation(libs.mosaic.testing)
            }
        }

        jvmTest {
            dependencies {
                implementation(projects.app.db.core)
                implementation(projects.app.db.write)
                implementation(projects.app.remotestorage.sync)
                implementation(projects.utils.localsettings)
                implementation(libs.ktor.client.core)
            }
        }
    }
}

// See app/main/cli: JetBrains' compose runtime-desktop is an empty pointer to androidx's from 1.9.
configurations.named("jvmRuntimeClasspath") {
    exclude(group = "org.jetbrains.compose.runtime", module = "runtime-desktop")
}
