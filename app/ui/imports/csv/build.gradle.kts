plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.core)

                implementation(libs.human.readable)
                implementation(libs.kmlogging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.app.csvimporter)
                implementation(projects.app.db.core)
                implementation(projects.app.importengineapi)
                implementation(projects.app.qifimporter)
                implementation(projects.app.ui.audit)
                implementation(projects.app.ui.components)
                implementation(projects.app.ui.foundation)
                implementation(projects.utils.compose.filePicker)
                implementation(projects.utils.compose.scrollbar)
                implementation(projects.utils.parsers.csv)
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
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)

                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.foundation.layout)
                implementation(libs.androidx.compose.material.icons.core)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.androidx.compose.ui.unit)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.ui.desktop)

                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.foundation.layout.desktop)
                implementation(libs.compose.material.icons.core.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.currentOs)
                implementation(libs.compose.ui.test.desktop)
            }
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.compose.ui.test)
                implementation(projects.test.app.ui)
            }
        }
    }
}
