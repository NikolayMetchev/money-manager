plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.core)

                implementation(projects.utils.compose.scrollbar)
                implementation(libs.kmlogging)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)

                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.androidx.compose.ui.unit)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.model.core)
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)

                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.diamondedge.logging)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
            }
        }
    }
}
