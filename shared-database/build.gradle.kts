plugins {
    id("moneymanager.android-convention")
    id("moneymanager.mappie-convention")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
            api(projects.shared)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        }

        val commonTest by getting {
            dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(projects.sharedDatabaseDi)
            implementation(projects.sharedDatabaseTest)
        }
        }

        val jvmMain by getting {
            dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        }

        val androidMain by getting {
            dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        }
    }
}

sqldelight {
    databases {
        create("MoneyManagerDatabase") {
            packageName.set("com.moneymanager.database")
            verifyMigrations.set(false)
        }
    }
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}
