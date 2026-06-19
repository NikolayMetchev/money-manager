plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.di.core)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.test)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(kotlin("test-junit"))
                api(projects.app.db.core)
                api(projects.app.di.core)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(kotlin("test-junit"))

                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.monitor)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
