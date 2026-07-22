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
                api(projects.app.model.csv)
                api(projects.app.model.importdirectory)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(projects.app.ui.foundation)

                implementation(projects.app.csvimporter)
                implementation(projects.app.importengineapi)
                implementation(projects.app.ui.audit)
                implementation(projects.app.ui.components)
                implementation(projects.utils.compose.filePicker)
                implementation(libs.kotlinx.coroutines.core)
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
                api(projects.app.importfilesource.core)
                api(projects.app.model.core)
                api(projects.app.model.importdirectory)
                api(projects.app.model.repository.read)
                api(projects.app.ui.foundation)
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)

                implementation(projects.app.model.csv)
                implementation(projects.app.model.qif)
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
