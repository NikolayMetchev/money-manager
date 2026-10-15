plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    alias(libs.plugins.compose.compiler)
}

// What screen presenters (#942) share: collecting repository flows into Compose state with schema-error
// handling. Compose runtime only, no Compose UI, so the desktop app, Android and the native TUI all use it.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(libs.androidx.compose.runtime)
                api(libs.kotlinx.coroutines.core)

                implementation(libs.kermit)
            }
        }

        jvmMain {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)

                implementation(libs.kermit.core)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.kermit.core)
            }
        }
    }
}

// Mosaic 0.18 brings JetBrains' compose runtime 1.8.2, which still carries its own classes on native. From 1.9
// that artifact only points at androidx's runtime, so lifting it leaves one copy of the runtime in a native
// binary that has both Mosaic and a presenter.
dependencies {
    constraints {
        add("commonMainApi", libs.compose.runtime)
    }
}
