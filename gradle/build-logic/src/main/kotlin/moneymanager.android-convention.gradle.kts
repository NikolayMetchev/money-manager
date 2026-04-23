import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("moneymanager.kotlin-convention")
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
// Android emulator API level sync: update with .github/workflows/build.yml,
// .idea/runConfigurations/Android_Tests.xml, and AGENTS.md.
val androidTestManagedDeviceNames = listOf("pixel6api36")

fun KotlinMultiplatformExtension.configureAndroidTarget() {
    android {
        // Use group (set by kotlin-convention based on project path)
        // Sanitize hyphens in group name for valid Android package name
        namespace = "com.moneymanager.${project.group.toString().replace("-", ".")}"
        compileSdk = libs.findVersion("android-compileSdk").get().toString().toInt()
        minSdk = libs.findVersion("android-minSdk").get().toString().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.findVersion("jvm-target").get().toString()))
        }

        lint {
            warningsAsErrors = true
            abortOnError = true
        }

        // Enable instrumented tests with Gradle Managed Device
        withDeviceTest {
            // Enable code coverage for instrumented tests
            enableCoverage = true

            managedDevices {
                localDevices {
                    create("pixel6api36") {
                        device = "Pixel 6"
                        apiLevel = 36
                        systemImageSource = "aosp-atd"
                    }
                }
                // Create a device group for running all tests
                groups {
                    create("allDevices") {
                        androidTestManagedDeviceNames.forEach { deviceName ->
                            targetDevices.add(allDevices[deviceName])
                        }
                    }
                }
            }
        }

        // Enable host tests so commonTest can be shared without KMP Android warnings.
        withHostTest {}
    }
}

kotlin {
    jvm()

    configureAndroidTarget()

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

// Include instrumented test compilation in the build task to catch compile errors early
tasks.named("build") {
    dependsOn("compileAndroidDeviceTest")
}

// Configure build scan integration for Android device tests
configureAndroidTestBuildScan(androidTestManagedDeviceNames)
