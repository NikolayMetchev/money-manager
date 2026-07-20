plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

// Read-only repository interfaces — the surface the UI and every read path talks to.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.accountmapping)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
                api(projects.app.model.timeline)
                api(libs.kotlinx.coroutines.core)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.accountmapping)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
                api(projects.app.model.timeline)
            }
        }
    }
}
