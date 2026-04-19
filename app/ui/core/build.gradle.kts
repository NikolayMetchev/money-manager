plugins {
    id("moneymanager.compose-multiplatform-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.kotlinx.coroutines.core)
                api(projects.app.db.core)
                api(projects.app.model.core)

                implementation(libs.compose.components.resources)
                implementation(libs.human.readable)
                implementation(libs.kmlogging)
                implementation(libs.kotlinx.datetime)
                implementation(projects.utils.bigdecimal)
                implementation(projects.utils.compose.filePicker)
                implementation(projects.utils.compose.scrollbar)
                implementation(projects.utils.currency)
                implementation(projects.utils.parsers.csv)
            }
        }
        val jvmAndroidMain by creating {
            dependsOn(commonMain)
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(projects.test.app.db)
            }
        }
        val androidMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(libs.androidx.compose.foundation)
                api(libs.androidx.compose.foundation.layout)
                api(libs.androidx.compose.runtime)
                api(libs.androidx.compose.ui)
                api(libs.androidx.compose.ui.geometry)
                api(libs.androidx.compose.ui.unit)

                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.compose.animation)
                implementation(libs.androidx.compose.animation.core)
                implementation(libs.androidx.compose.material.icons.core)
                implementation(libs.androidx.compose.material3)
                implementation(libs.androidx.compose.ui.graphics)
                implementation(libs.androidx.compose.ui.text)
                implementation(libs.diamondedge.logging)
            }
        }
        val jvmMain by getting {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                api(libs.compose.foundation.desktop)
                api(libs.compose.foundation.layout.desktop)
                api(libs.compose.ui.desktop)
                api(libs.compose.ui.geometry.desktop)
                api(libs.compose.ui.unit.desktop)
                api(projects.app.db.core)
                api(projects.app.model.core)

                implementation(libs.compose.animation.core.desktop)
                implementation(libs.compose.animation.desktop)
                implementation(libs.compose.components.resources.desktop)
                implementation(libs.compose.material.desktop)
                implementation(libs.compose.material.icons.core.desktop)
                implementation(libs.compose.material3.desktop)
                implementation(libs.compose.ui.graphics.desktop)
                implementation(libs.compose.ui.text.desktop)
                implementation(libs.diamondedge.logging)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // Skiko native libraries for desktop UI tests
                implementation(compose.desktop.currentOs)
                implementation(libs.androidx.compose.runtime.desktop)
                implementation(libs.compose.ui.test.desktop)
                implementation(libs.sqldelight.runtime)
                implementation(projects.app.di.core)
            }
        }
        val androidDeviceTest by getting {
            // Note: Cannot use dependsOn(commonTest) due to source set tree restrictions
            // Tests are shared via kotlin.srcDir() below
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.compose.runtime)
                implementation(libs.androidx.compose.ui.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.sqldelight.runtime)
                implementation(projects.app.di.core)
                implementation(projects.test.app.db)
            }
            kotlin.srcDir("src/commonTest/kotlin")
            // Include commonTest resources for test database files
            resources.srcDir("src/commonTest/resources")
        }
    }
}

// Configure tests to run in headless mode for Compose Desktop
tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
    // Additional properties for Skiko on Windows
    systemProperty("skiko.test.harness", "true")
}
