plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.mokkery)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.model.repository.read)

                implementation(projects.app.importengineapi)
                implementation(projects.app.ui.audit)
                implementation(projects.app.ui.components)
                implementation(projects.app.ui.foundation)
                implementation(projects.utils.compose.scrollbar)
                implementation(libs.kmlogging)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(projects.test.app.ui)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mokkery.runtime)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)

                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.androidx.compose.ui.unit)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.model.repository.read)
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)

                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(projects.app.model.repository.write)
                implementation(kotlin("test"))
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.ui.test.desktop)
                implementation(libs.mokkery.core)
            }
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(projects.app.model.repository.write)
                implementation(projects.test.app.ui)
                implementation(libs.androidx.compose.ui.test)
                implementation(libs.mokkery.core)
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(projects.app.model.repository.write)
            }
        }
    }
}
