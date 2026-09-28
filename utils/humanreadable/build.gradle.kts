plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
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
