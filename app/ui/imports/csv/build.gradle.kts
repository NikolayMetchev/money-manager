plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.csvimporter)
                api(projects.app.importengineapi)
                api(projects.app.model.accountmapping)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(projects.app.model.timeline)

                implementation(projects.app.ui.audit)
                implementation(projects.app.ui.components)
                implementation(projects.app.ui.foundation)
                implementation(projects.utils.compose.filePicker)
                implementation(projects.utils.compose.scrollbar)
                implementation(projects.utils.parsers.csv)
                implementation(projects.utils.parsers.xlsx)
                implementation(libs.human.readable)
                implementation(libs.kmlogging)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(projects.test.app.ui)
                implementation(kotlin("test"))
            }
        }
        getByName("androidMain") {
            dependencies {
                api(projects.app.db.core)
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)

                implementation(projects.app.db.read)
                implementation(projects.app.model.passthrough)
                implementation(projects.utils.bigdecimal)
                implementation(libs.androidx.compose.animation)
                implementation(libs.androidx.compose.animation.core)
                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.material.icons.core)
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
                api(projects.app.csvimporter)
                api(projects.app.db.core)
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.model.csv)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.qif)
                api(projects.app.model.repository.read)
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)
                api(libs.compose.ui.desktop)

                implementation(projects.app.db.read)
                implementation(projects.app.model.accountmapping)
                implementation(projects.app.model.passthrough)
                implementation(projects.app.model.timeline)
                implementation(projects.utils.bigdecimal)
                implementation(libs.compose.animation.core.desktop)
                implementation(libs.compose.animation.desktop)
                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.material.icons.core.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.datetime)
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
                implementation(projects.test.app.ui)
                implementation(libs.androidx.compose.ui.test)
            }
        }
    }
}
