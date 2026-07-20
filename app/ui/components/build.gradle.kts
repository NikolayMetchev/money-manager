plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.repository.read)
                api(projects.app.ui.foundation)

                implementation(projects.utils.currency)
                implementation(libs.kmlogging)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("androidMain") {
            dependencies {
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)

                implementation(libs.androidx.compose.foundation)
                implementation(libs.androidx.compose.material.icons.core)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.androidx.compose.ui.unit)
                implementation(libs.diamondedge.logging)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.app.db.core)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.repository.read)
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)
                api(libs.compose.ui.desktop)

                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.material.icons.core.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.diamondedge.logging)
            }
        }
    }
}
