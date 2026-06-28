plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.sqldelight.runtime)
                // Write repositories use read-side Mappie mappers and JSON codecs, and the wrapper exposes
                // SqlDriver/QueryResult; the CSV read repository (raw dynamic-table access via the wrapper)
                // lives here too and uses coroutine Flows.
                api(projects.app.db.read)
                // Both a normal dependency (for generated row types) AND the sqldelight dependency() below
                // (for .sq cross-module schema resolution) are required.
                api(projects.app.db.schema)
                api(projects.app.model.core)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.coroutines.extensions)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.read)
                api(projects.app.db.schema)
                api(projects.app.model.core)

                implementation(libs.kotlinx.serialization.core)
            }
        }
        getByName("androidMain") {
            dependencies {
                implementation(libs.kotlinx.serialization.core)
            }
        }
    }
}

// Write side: *Write.sq plus the write repository implementations, the MoneyManagerDatabaseWrapper
// (composing read+write databases over one driver), source recording, and the dynamic-CSV-table helpers.
// Generates MoneyManagerDatabase in com.moneymanager.database.sql.write. Needs SQLDelight 2.4.0+ for
// Gradle Isolated Projects support (sqldelight#6259).
sqldelight {
    databases {
        create("MoneyManagerDatabase") {
            packageName.set("com.moneymanager.database.sql.write")
            verifyMigrations.set(false)
            dialect(libs.sqldelight.dialect.sqlite)
            dependency(project(":app:db:schema"))
        }
    }
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}
