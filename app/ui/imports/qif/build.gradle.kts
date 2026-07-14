plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.accountmapping)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(projects.app.model.timeline)
                api(projects.app.qifimporter)
                api(projects.utils.parsers.qif)

                implementation(libs.kmlogging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.app.csvimporter)
                implementation(projects.app.ui.components)
                implementation(projects.app.ui.foundation)
                implementation(projects.app.ui.imports.csv)
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
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)
                api(libs.compose.ui.desktop)
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(projects.app.qifimporter)
                api(projects.utils.parsers.qif)

                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.datetime)
                implementation(projects.app.model.accountmapping)
                implementation(projects.app.model.csv)
                implementation(projects.app.model.csvstrategy)
                implementation(projects.app.model.timeline)
            }
        }
    }
}
