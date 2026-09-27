import com.android.build.api.variant.TestAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Black-box smoke test of the R8-minified release APK. The instrumented tests elsewhere only ever run
// the unminified debug build, so R8 stripping something reached only by reflection or ServiceLoader
// would compile fine and crash the shipped app. This self-instrumenting test module runs in its own
// process and drives the installed release app through UiAutomator, so it never shares (or clashes
// with) the app's obfuscated classes.
//
// The release variant only exists with -PreleaseSmokeTest=true, which also debug-signs it:
//   ./gradlew :app:main:android-smoketest:pixel6api36ReleaseAndroidTest -PreleaseSmokeTest=true --console=plain
plugins {
    id("moneymanager.kotlin-convention")
    id("com.android.test")
}

val jvmTargetVersion =
    libs.versions.jvm.target
        .get()
val releaseSmokeTest = providers.gradleProperty("releaseSmokeTest").map(String::toBoolean).getOrElse(false)

kotlin {
    jvmToolchain(
        libs.versions.jvm.toolchain
            .get()
            .toInt(),
    )

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(jvmTargetVersion))
    }
}

android {
    namespace = "com.moneymanager.android.smoketest"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()
    targetProjectPath = ":app:main:android"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // A test module only has "debug"; this one targets the app's release variant by name.
        create("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jvmTargetVersion)
        targetCompatibility = JavaVersion.toVersion(jvmTargetVersion)
    }

    testOptions {
        managedDevices {
            localDevices {
                // Android emulator API level sync: matches moneymanager.android-convention's device.
                create("pixel6api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

// Only the release variant is worth testing, and it can only resolve against the app's release
// variant, which exists solely under -PreleaseSmokeTest=true. Otherwise the module has no variants.
configure<TestAndroidComponentsExtension> {
    beforeVariants { variant ->
        variant.enable = releaseSmokeTest && variant.buildType == "release"
    }
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.junit)
}
