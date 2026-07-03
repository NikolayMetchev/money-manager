plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.core)
                api(projects.app.remotestorage.core)
                api(projects.app.remotestorage.sync)
                api(projects.app.strategycatalog)
                api(projects.app.ui.foundation)

                implementation(libs.compose.charts)
                implementation(libs.human.readable)
                implementation(libs.kotlinx.coroutines.core)
                implementation(projects.app.importengineapi)
                implementation(projects.app.remotestorage.googledrive)
                implementation(projects.app.ui.components)
                implementation(projects.utils.compose.scrollbar)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)
                api(projects.app.db.write)

                implementation(libs.androidx.compose.animation.core)
                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.material.icons.core)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui.geometry)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.androidx.compose.ui.unit)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)
                api(projects.app.db.write)
                api(projects.app.model.core)
                api(projects.app.remotestorage.core)
                api(projects.app.remotestorage.sync)

                implementation(libs.compose.animation.core.desktop)
                implementation(libs.compose.charts.desktop)
                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.material.icons.core.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.desktop)
                implementation(libs.compose.ui.geometry.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(projects.app.ui.foundation)
            }
        }
    }
}
