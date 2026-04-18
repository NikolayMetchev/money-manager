plugins {
    id("moneymanager.android-convention")
    id("moneymanager.mappie-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(libs.mappie.api)
                api(projects.app.model.core)
                api(projects.utils.currency)

                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.coroutines.extensions)
                implementation(projects.utils.bigdecimal)
            }
        }

        val commonTest by getting {
            dependencies {
                api(projects.app.model.core)

                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.test.app.db)
            }
        }

        val jvmMain by getting {
            dependencies {
                api(projects.utils.currency)

                implementation(libs.kotlinx.serialization.core)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(projects.app.db.core)
                implementation(projects.app.di.core)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.sqlite)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.sqldelight.android.driver)
            }
        }

        val androidDeviceTest by getting {
            // Note: Cannot use dependsOn(commonTest) due to source set tree restrictions
            // Tests are shared via srcDir() below, but we exclude the expect declarations
            // file since androidDeviceTest provides its own implementation
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.app.di.core)
                implementation(projects.test.app.db)
                runtimeOnly(libs.androidx.test.runner)
            }
            // Include repository tests from commonTest (not the expect declarations file)
            kotlin.srcDir("src/commonTest/kotlin/com/moneymanager/database/repository")
        }
    }
}

sqldelight {
    databases {
        create("MoneyManagerDatabase") {
            packageName.set("com.moneymanager.database.sql")
            verifyMigrations.set(false)
        }
    }
}

tasks.withType<app.cash.sqldelight.gradle.VerifyMigrationTask>().configureEach {
    enabled = false
}
