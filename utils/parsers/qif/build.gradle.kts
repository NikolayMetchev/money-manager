plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
    id("moneymanager.native-convention")
}

kotlin {
    sourceSets {
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
