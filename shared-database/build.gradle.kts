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
                implementation(projects.sharedDatabaseTest)
                implementation(projects.sharedDi)
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
            // Tests are shared via kotlin.srcDir() below
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.runner)
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.sharedDatabaseTest)
                implementation(projects.sharedDi)
            }
            kotlin.srcDir("src/commonTest/kotlin")
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
