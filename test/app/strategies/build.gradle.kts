plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// DB-free built-in strategy definitions (Kotlin is the authoring source; the shipped JSON artifacts in
// strategy-library/ are exported from these by :tools:strategy-catalog and kept in lockstep by its
// tests). Consumed by importer mapper tests, DbTest installer helpers, and the catalog tool.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.core)
            }
        }
    }
}
