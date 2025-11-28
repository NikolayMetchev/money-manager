import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("moneymanager.kotlin-convention")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    jvm()

    // Configure Android target using the new plugin's DSL
    androidLibrary {
        namespace = "com.moneymanager.${project.name.replace("-", ".")}"
        compileSdk = libs.findVersion("android-compileSdk").get().toString().toInt()
        minSdk = libs.findVersion("android-minSdk").get().toString().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.findVersion("jvm-target").get().toString()))
        }

        // Enable instrumented tests with Gradle Managed Device
        withDeviceTest {
            // Enable code coverage for instrumented tests
            enableCoverage = true

            managedDevices {
                localDevices {
                    // Create a managed device named "pixel6api34"
                    create("pixel6api34") {
                        device = "Pixel 6"
                        apiLevel = 34
                        systemImageSource = "aosp-atd"
                    }
                }
                // Create a device group for running all tests
                groups {
                    create("allDevices") {
                        targetDevices.add(allDevices["pixel6api34"])
                    }
                }
            }
        }
    }

    jvmToolchain(libs.findVersion("jvm-toolchain").get().toString().toInt())

    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// Override ktlint android setting for Android modules
ktlint {
    android.set(true)
}
