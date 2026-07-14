plugins {
    id("moneymanager.android-convention")
    id("moneymanager.metro-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.di.params)
                api(projects.app.di.scope)
                api(projects.utils.localsettings)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.di.params)
                api(projects.app.di.scope)
                api(projects.utils.localsettings)
            }
        }
    }
}
