plugins {
    id("moneymanager.native-convention")
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

// Local-folder backend for ImportFileSource: lists + reads files from a filesystem folder over
// kotlinx-io (utils/files), one implementation for every platform; androidMain adds the SAF
// document-tree variant on top. Database-free.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(projects.app.importfilesource.core)

                implementation(projects.utils.files)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.io.core)
            }
        }

        jvmMain {
            dependencies {
                api(projects.app.importfilesource.core)
            }
        }

        androidMain {
            dependencies {
                api(projects.app.importfilesource.core)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
