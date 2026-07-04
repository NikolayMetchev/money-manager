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
                api(projects.app.strategies)

                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.utils.currency)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(kotlin("test-junit"))
                api(projects.app.db.core)
                api(projects.app.db.write)
                api(projects.app.di.core)
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.app.importengineapi)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(kotlin("test-junit"))
                api(projects.app.db.write)

                implementation(libs.androidx.test.monitor)
                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.app.importengineapi)
            }
        }
    }
}
