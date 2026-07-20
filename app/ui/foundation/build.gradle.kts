plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.repository.read)
                api(projects.utils.bigdecimal)
                api(projects.utils.localsettings)
                api(libs.androidx.navigation3.runtime)
                api(libs.androidx.savedstate)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.serialization.core)

                implementation(projects.utils.currency)
                implementation(libs.kmlogging)
            }
        }
        val jvmAndroidMain =
                create("jvmAndroidMain") {
                dependsOn(getByName("commonMain"))
            }
        getByName("commonTest") {
            dependencies {
                implementation(projects.test.app.ui)
                implementation(kotlin("test"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
        getByName("androidMain") {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(libs.androidx.compose.foundation)
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)
                api(libs.androidx.compose.ui.unit)

                implementation(libs.androidx.compose.material.icons.core)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.runtime.annotation)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.diamondedge.logging)
            }
        }
        getByName("jvmMain") {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(projects.app.importengineapi)
                api(projects.app.model.core)
                api(projects.app.model.csvstrategy)
                api(projects.app.model.repository.read)
                api(projects.utils.bigdecimal)
                api(libs.androidx.compose.runtime.desktop)
                api(libs.androidx.navigation3.runtime.desktop)
                api(libs.androidx.savedstate.desktop)
                api(libs.compose.foundation.desktop)
                api(libs.compose.ui.desktop)

                implementation(libs.androidx.compose.runtime.annotation)
                implementation(libs.compose.foundation.layout.desktop)
                implementation(libs.compose.material.icons.core.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.compose.ui.util.desktop)
                implementation(libs.diamondedge.logging)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.currentOs)
                implementation(libs.androidx.navigation3.runtime.desktop)
                implementation(libs.androidx.savedstate.desktop)
                implementation(libs.compose.ui.test.desktop)
            }
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(projects.test.app.ui)
                implementation(kotlin("test"))
                implementation(libs.androidx.compose.ui.test)
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}
