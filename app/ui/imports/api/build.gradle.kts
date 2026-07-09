plugins {
    id("moneymanager.compose-ui-feature-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.app.model.core)

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(projects.app.apiimporter)
                implementation(projects.app.db.read)
                implementation(projects.app.importengineapi)
                implementation(projects.app.ui.audit)
                implementation(projects.app.ui.foundation)
                implementation(projects.utils.compose.filePicker)
                implementation(projects.utils.compose.scrollbar)
                implementation(projects.utils.rest)
            }
        }
        val jvmAndroidMain =
            create("jvmAndroidMain") {
                dependsOn(getByName("commonMain"))
            }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("androidMain") {
            dependsOn(jvmAndroidMain)
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
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.client.core)
            }
        }
        getByName("jvmMain") {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.layout.desktop)
                api(projects.app.model.core)

                implementation(libs.compose.foundation.desktop)
                implementation(libs.compose.material.icons.core.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.compose.ui.unit.desktop)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.core)
                implementation(libs.ktor.client.core)
            }
        }
        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("androidDeviceTest") {
            dependencies {
                // These are pure-logic tests (no Compose UI), so unlike the other feature
                // modules nothing pulls in the instrumentation runner transitively; provide
                // it explicitly so the device-test APK can launch AndroidJUnitRunner.
                runtimeOnly(libs.androidx.test.runner)
            }
        }
    }
}
