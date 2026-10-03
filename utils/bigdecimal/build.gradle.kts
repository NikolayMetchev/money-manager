plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    // Shared jvmAndroidMain source set: both platforms have java.math.BigDecimal.
    id("moneymanager.jvm-android-shared-convention")
}

kotlin {
    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
