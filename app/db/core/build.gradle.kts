plugins {
    id("moneymanager.android-convention")
    id("moneymanager.mappie-convention")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.sqldelight.coroutines.extensions)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.app.di.core)
                implementation(projects.app.model.core)
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

        val androidDeviceTest by getting {
            // Note: Cannot use dependsOn(commonTest) due to source set tree restrictions
            // Tests are shared via srcDir() below, but we exclude the expect declarations
            // file since androidDeviceTest provides its own implementation
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.runner)
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.app.di.core)
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
