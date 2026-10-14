plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
    alias(libs.plugins.compose.compiler)
}

// Terminal widgets for background tasks, on Mosaic: the CLI's live progress region now, the TUI (#918) later.
// Outside app/ui on purpose: those modules are Compose UI for JVM and Android, this one also runs natively.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.tasks)
                api(libs.kotlinx.coroutines.core)
                api(libs.mosaic.runtime)

                implementation(libs.atomicfu)
                implementation(libs.kermit)
            }
        }

        // Common code reaches these through Mosaic's api, which is all a native compile needs. buildHealth only
        // reads JVM bytecode, and wants the ones it sees used declared here.
        jvmMain {
            dependencies {
                api(libs.androidx.compose.runtime.desktop)
                api(libs.mosaic.terminal)

                implementation(libs.kermit.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mosaic.testing)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.androidx.compose.runtime.desktop)
                implementation(libs.mosaic.terminal)
            }
        }
    }
}

// Mosaic 0.18 was built on JetBrains' compose runtime 1.8.2, which still carried its own classes on the JVM.
// From 1.9 that artifact only points at androidx's runtime, so lifting it to the desktop app's version leaves
// one copy of every runtime class instead of two.
dependencies {
    constraints {
        add("jvmMainApi", libs.compose.runtime.desktop)
    }
}
