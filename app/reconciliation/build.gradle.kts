plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.pure-importer-convention")
}

// Source-agnostic reconciliation: matches a reconciliation source's shadow-account legs against the
// real accounts they are linked to, and plans automatic shadow→real links. DB-free (enforced).
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.model.reconciliation)
            }
        }

        getByName("jvmMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.model.reconciliation)

                implementation(projects.utils.bigdecimal)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(projects.utils.bigdecimal)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
