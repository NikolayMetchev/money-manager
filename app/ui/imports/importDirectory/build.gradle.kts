plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

// Import Directories tab UI: configure local/Drive folders to download importable files from. The
// scan/download lives in app/csvimporter; this module is just the Compose screen + dialogs.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.importfilesource.core)
                api(projects.app.model.core)
                api(projects.app.ui.foundation)

                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.app.csvimporter)
                implementation(projects.app.importengineapi)
                implementation(projects.utils.compose.filePicker)
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
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)
                api(projects.app.importfilesource.core)
                api(projects.app.model.core)
                api(projects.app.ui.foundation)

                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
            }
        }
    }
}
