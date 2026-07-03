import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(conventions.plugins.moneymanager.kotlin.convention)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Android emulator API level sync: update with .github/workflows/build.yml,
// .idea/runConfigurations/Android_Tests.xml, and AGENTS.md.
val androidTestManagedDeviceNames = listOf("pixel6api36")

fun KotlinMultiplatformExtension.configureAndroidTarget() {
    android {
        // Use group (set by kotlin-convention based on project path)
        // Sanitize hyphens in group name for valid Android package name
        namespace = "com.moneymanager.${project.group.toString().replace("-", ".")}"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvm.target.get()))
        }

        lint {
            moneyManagerLintPolicy()
        }

        // Enable instrumented tests with Gradle Managed Device
        withDeviceTest {
            // JaCoCo instrumentation slows every instrumented test run, so coverage is opt-in:
            // the main-branch CI job passes -PandroidCoverage=true; PR runs skip it.
            enableCoverage = providers.gradleProperty("androidCoverage").map(String::toBoolean).getOrElse(false)

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

    jvmToolchain(libs.versions.jvm.toolchain.get().toInt())

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        getByName("jvmMain") {
            dependencies {
                runtimeOnly(libs.kotlinx.coroutines.swing)
            }
        }
        getByName("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }

    configureAndroidTarget()
}

// Override ktlint android setting for Android modules
ktlint {
    android.set(true)
}

// Instrumented test sources are compiled by the CI emulator job (connectedAndroidDeviceTest) on
// every PR, so `build` skips them by default; opt in locally with -PcompileDeviceTests=true to
// catch device-test compile errors early.
if (providers.gradleProperty("compileDeviceTests").map(String::toBoolean).getOrElse(false)) {
    tasks.named("build") {
        dependsOn("compileAndroidDeviceTest")
    }
}

// Configure build scan integration for Android device tests
configureAndroidTestBuildScan(androidTestManagedDeviceNames)
