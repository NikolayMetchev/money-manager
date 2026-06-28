plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                // api: read's generated database + select queries expose schema's row types.
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

// Read side: owns every *Select.sq. Generates MoneyManagerDatabase in com.moneymanager.database.sql.read,
// reusing the schema module's tables/row types via dependency(). Does NOT depend on :app:db:write.
sqldelight {
    databases {
        create("MoneyManagerDatabase") {
            packageName.set("com.moneymanager.database.sql.read")
            verifyMigrations.set(false)
            dialect(libs.sqldelight.dialect.sqlite)
            dependency(project(":app:db:schema"))
        }
    }
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}
