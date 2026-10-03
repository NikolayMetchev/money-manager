plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    // Shared jvmAndroidMain source set: both platforms have java.text.NumberFormat.
    id("moneymanager.jvm-android-shared-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.utils.bigdecimal)
            }
        }

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
