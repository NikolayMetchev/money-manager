plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

// The read repository implementations. Split from :app:db:read — which owns the generated SQL, the
// Mappie mappers and the JSON codecs — so that editing a query implementation does not recompile the
// mappers, and vice versa. The dependency runs impls -> mappers, never the other way.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.db.read)
                api(projects.app.db.schema)
                api(projects.app.model.accountmapping)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(projects.app.model.timeline)
                api(libs.kotlinx.coroutines.core)

                implementation(projects.utils.bigdecimal)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.coroutines.extensions)
            }
        }
        getByName("androidMain") {
            dependencies {
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.sqldelight.runtime)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.read)
                api(projects.app.model.accountmapping)
                api(projects.app.model.apistrategy)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.importdirectory)
                api(projects.app.model.passthrough)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(projects.app.model.timeline)

                implementation(libs.kotlinx.serialization.core)
                implementation(libs.sqldelight.runtime)
            }
        }
    }
}
