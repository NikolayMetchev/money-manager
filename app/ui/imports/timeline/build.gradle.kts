plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(libs.kotlinx.datetime)
                api(projects.app.model.repository.read)
                api(projects.app.model.timeline)

                implementation(projects.app.ui.foundation)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(projects.test.app.ui)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)

                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui.geometry)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.androidx.compose.ui.unit)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                api(projects.app.model.repository.read)
                api(projects.app.model.timeline)

                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.foundation.layout.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.desktop)
                implementation(libs.compose.ui.geometry.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.compose.ui.test.desktop)
                implementation(libs.kotlinx.coroutines.core)

                runtimeOnly(compose.desktop.currentOs)
            }
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.compose.ui.test)
                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.test.app.ui)
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
