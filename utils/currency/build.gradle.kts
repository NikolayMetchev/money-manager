plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.utils.bigdecimal)
            }
        }

        // Shared source set for JVM and Android (both have access to java.text.NumberFormat)
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
        }

        jvmMain {
            dependsOn(jvmAndroidMain)
            dependencies {
                api(projects.utils.bigdecimal)
            }
        }

        androidMain {
            dependsOn(jvmAndroidMain)
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
