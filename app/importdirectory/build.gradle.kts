plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    id("moneymanager.pure-importer-convention")
}

// Import-directory orchestration free of Compose and the database implementation: download every folder
// (with subfolder discovery), re-download, and plan + apply CSV/QIF "import all" / "re-import all". The
// desktop app, Android and the native CLI run the same code.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.csvimporter)
                api(projects.app.importengineapi)
                api(projects.app.importfilesource.core)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(projects.app.qifimporter)
                api(projects.app.tasks)
                api(libs.kotlinx.coroutines.core)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.csvimporter)
                api(projects.app.importengineapi)
                api(projects.app.importfilesource.core)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(projects.app.qifimporter)
                api(projects.app.tasks)
            }
        }
    }
}
