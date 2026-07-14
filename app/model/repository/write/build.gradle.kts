plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

// Write repository interfaces. Only the ImportEngine — and the DI wiring that builds it — may depend on this.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.accountmapping)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.accountmapping)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
            }
        }
    }
}
