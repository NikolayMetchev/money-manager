plugins {
    id("moneymanager.android-convention")
    id("moneymanager.metro-convention")
}

// The database-scoped graph and everything that fills it: the repository bindings, the ImportEngine
// factory, and the read-only Application view the UI receives.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.db.write)
                api(projects.app.di.params)
                api(projects.app.di.scope)
                api(projects.app.importengineapi)
                api(projects.app.importer)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.repository.read)
                api(projects.app.model.repository.write)

                implementation(projects.app.db.read)
                implementation(projects.app.db.repository)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.db.write)
                api(projects.app.di.params)
                api(projects.app.di.scope)
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.repository.read)
                api(projects.app.model.repository.write)
            }
        }
    }
}
