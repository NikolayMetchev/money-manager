plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.core)

                implementation(libs.kmlogging)
                implementation(projects.app.ui.foundation)
                implementation(projects.utils.compose.scrollbar)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(libs.androidx.compose.runtime)

                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.foundation.layout)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.androidx.compose.ui.unit)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)

                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.foundation.layout.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
            }
        }
    }
}
