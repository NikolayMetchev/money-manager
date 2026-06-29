plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                // Both a normal dependency (for generated schema types) AND the sqldelight dependency()
                // below (for .sq cross-module schema resolution) are required.
                api(projects.app.db.schema)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.schema)
            }
        }
    }
}

// The seed module: holds seed INSERTs (no DDL). dependency(:schema) makes the schema module's tables +
// triggers run during Schema.create FIRST, then these INSERTs — so audited seeds (category, currency,
// strategies) fire their audit triggers exactly as the old runtime seeding did. Static seeds are the
// checked-in .sq here; currencies + strategies are generated as .sq at build time (Phase 2).
sqldelight {
    databases {
        create("MoneyManagerDatabase") {
            packageName.set("com.moneymanager.database.sql.seed")
            verifyMigrations.set(false)
            dialect(libs.sqldelight.dialect.sqlite)
            dependency(project(":app:db:schema"))
        }
    }
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}
