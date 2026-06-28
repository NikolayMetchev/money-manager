plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                // Both a normal dependency (for generated row types) AND the sqldelight dependency() below
                // (for .sq cross-module schema resolution) are required. api, not implementation: the
                // generated write database's public API exposes schema's row types.
                api(projects.app.db.schema)
            }
        }
        // dependency-analysis wants the JVM variant's use of schema declared in jvmMain too.
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.schema)
            }
        }
    }
}

// Write side: owns every *Write.sq (DML + materialized-view refresh). Generates its own
// MoneyManagerDatabase in com.moneymanager.database.sql.write, reusing the schema module's tables via
// dependency(). Does NOT depend on :app:db:read. Needs SQLDelight 2.4.0+ for Gradle Isolated Projects
// support (sqldelight#6259).
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
