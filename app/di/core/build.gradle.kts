plugins {
    id("moneymanager.android-convention")
    id("moneymanager.metro-convention")
}

// The application graph. Metro merges every @ContributesTo(AppScope) module it finds on this module's
// compile classpath, so the feature DI modules are dependencies purely to be *seen* — no code here names
// them. Drop one and the graph quietly loses its bindings, so they stay listed even though nothing
// imports them.
//
// Since Metro 1.4.x these are `@BindingContainer` objects, which Metro *merges* into the generated
// AppComponent rather than making them supertypes of it, so they no longer have to leak onto a
// consumer's compile classpath -- `implementation` is enough for the ones a consumer never touches.
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
                implementation(projects.app.remotestorage.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.di.params)
                api(projects.app.di.scope)
                api(projects.app.model.core)
                api(projects.app.remotestorage.sync)
                api(projects.app.strategycatalog)
                api(projects.utils.localsettings)
                implementation(projects.app.db.di)
                implementation(projects.app.remotestorage.core)
                implementation(projects.app.remotestorage.di)
                implementation(projects.app.strategycatalog.di)
                implementation(projects.utils.localsettings.di)
            }
        }
    }
}
