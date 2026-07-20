plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.db.di)
                api(projects.app.model.core)
                api(projects.app.model.repository.write)
                api(projects.app.strategies)

                implementation(projects.utils.currency)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.db.di)
                api(projects.app.db.write)
                api(projects.app.model.core)
                api(projects.app.model.repository.write)
                api(kotlin("test-junit"))

                implementation(projects.app.importengineapi)
                implementation(projects.app.model.apistrategy)
                implementation(projects.app.model.csvstrategy)
                implementation(projects.app.model.passthrough)
                implementation(projects.utils.bigdecimal)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(projects.app.db.write)
                api(kotlin("test-junit"))

                implementation(projects.app.importengineapi)
                implementation(projects.app.model.apistrategy)
                implementation(projects.app.model.csvstrategy)
                implementation(projects.app.model.passthrough)
                implementation(projects.utils.bigdecimal)
                implementation(libs.androidx.test.monitor)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
