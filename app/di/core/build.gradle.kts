plugins {
    id("moneymanager.android-convention")
    id("moneymanager.metro-convention")
}

// The application graph. Metro merges every @ContributesTo(AppScope) module it finds on this module's
// compile classpath, so the feature DI modules are dependencies purely to be *seen* — no code here names
// them. Drop one and the graph quietly loses its bindings, so they stay listed even though nothing
// imports them.
//
// They are `api` rather than `implementation` because Metro makes each contributed module a *supertype*
// of the generated AppComponent: anything that touches AppComponent needs them on its compile classpath.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.db.di)
                api(projects.app.di.params)
                api(projects.app.di.scope)
                api(projects.app.model.core)
                api(projects.app.remotestorage.di)
                api(projects.app.remotestorage.sync)
                api(projects.app.strategycatalog)
                api(projects.app.strategycatalog.di)
                api(projects.utils.localsettings)
                api(projects.utils.localsettings.di)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(projects.app.remotestorage.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.db.di)
                api(projects.app.di.params)
                api(projects.app.di.scope)
                api(projects.app.model.core)
                api(projects.app.remotestorage.core)
                api(projects.app.remotestorage.di)
                api(projects.app.remotestorage.sync)
                api(projects.app.strategycatalog)
                api(projects.app.strategycatalog.di)
                api(projects.utils.localsettings)
                api(projects.utils.localsettings.di)
            }
        }
    }
}
