plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.sqldelight)
}

// The schema module: owns ALL DDL (CREATE TABLE/INDEX/VIEW). Generates the row types and the
// MoneyManagerDatabase + Schema in com.moneymanager.database.sql. :app:db:read and :app:db:write both
// depend on this (they own *Select.sq and *Write.sq respectively); neither depends on the other. This is
// also the future home for database migrations (.sqm).
sqldelight {
    databases {
        create("MoneyManagerDatabase") {
            packageName.set("com.moneymanager.database.sql")
            verifyMigrations.set(false)
            dialect(libs.sqldelight.dialect.sqlite)
        }
    }
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}
