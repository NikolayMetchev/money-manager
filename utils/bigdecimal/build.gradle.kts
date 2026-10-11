plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    // Shared jvmAndroidMain source set: both platforms have java.math.BigDecimal.
    id("moneymanager.jvm-android-shared-convention")
    id("moneymanager.native-convention")
}

kotlin {
    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        nativeMain {
            dependencies {
                // Integer arithmetic only: the native BigDecimal is its own (unscaled, scale) pair so it
                // can mirror java.math exactly, which ionspin's BigDecimal does not.
                implementation(libs.bignum)
            }
        }
    }
}
