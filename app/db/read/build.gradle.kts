plugins {
    id("moneymanager.android-convention")
    id("moneymanager.mappie-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.mappie.api)
                // api: read's generated database + select queries + mappers expose schema's row types and
                // domain models.
                api(projects.app.db.schema)
                api(projects.app.model.core)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.coroutines.extensions)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
                api(projects.app.db.schema)
                api(projects.app.model.core)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// Read side: schema + *Select.sq, plus the read repository implementations, Mappie mappers and JSON
// codecs. Generates MoneyManagerDatabase in com.moneymanager.database.sql.read; does NOT depend on
// :app:db:write.
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
