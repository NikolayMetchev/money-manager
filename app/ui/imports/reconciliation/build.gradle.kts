plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.mokkery)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.reconciliation)
                api(projects.app.model.repository.read)
                api(projects.app.reconciliation)
                api(libs.kotlinx.datetime)

                implementation(projects.app.ui.components)
                implementation(projects.app.ui.foundation)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(projects.test.app.ui)
                implementation(kotlin("test"))
                implementation(libs.mokkery.runtime)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(projects.app.model.core)
                api(libs.androidx.compose.foundation)
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)

                implementation(projects.app.importengineapi)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.androidx.compose.ui.unit)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.model.reconciliation)
                api(projects.app.model.repository.read)
                api(projects.app.reconciliation)
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.desktop)
                api(libs.compose.foundation.layout.desktop)

                implementation(projects.app.importengineapi)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(projects.app.model.core)
                implementation(kotlin("test"))
                implementation(libs.compose.ui.test.desktop)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.mokkery.core)

                runtimeOnly(compose.desktop.currentOs)
            }
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(projects.app.model.core)
                implementation(projects.test.app.ui)
                implementation(libs.androidx.compose.ui.test)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.mokkery.core)
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(projects.app.model.core)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.mokkery.core)
            }
        }
    }
}
