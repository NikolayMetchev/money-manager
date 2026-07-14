plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// DB-free built-in strategy definitions — the source of the strategy catalog. Nothing is checked in
// or seeded: :tools:strategy-catalog renders these to webpage/strategy-library on each Pages deploy.
// Consumed by the catalog tool, importer mapper tests, and the DbTest installer helpers.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.apistrategy)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.passthrough)

                implementation(projects.app.model.core)
                implementation(projects.app.model.qif)
            }
        }
    }
}
