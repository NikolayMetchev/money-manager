plugins {
    id("moneymanager.compose-multiplatform-convention")
    alias(libs.plugins.compose.compiler)
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                implementation(compose.foundation)
                implementation(compose.runtime)
            }
        }

        jvmMain {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}
