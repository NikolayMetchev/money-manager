plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    // Shared jvmAndroidMain source set: both platforms have java.text.NumberFormat.
    id("moneymanager.jvm-android-shared-convention")
    // The native actual formats from the bundled ISO table (IsoCurrencies) instead of java.util.Currency.
    id("moneymanager.native-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.utils.bigdecimal)
            }
        }

        // buildHealth wants the jvm variant to declare it too, not just inherit it from commonMain.
        jvmMain {
            dependencies {
                api(projects.utils.bigdecimal)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
