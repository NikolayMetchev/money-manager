plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.jvm-android-shared-convention")
}

// Local-folder backend for ImportFileSource: lists + reads files from a filesystem folder via
// java.io.File. JVM and Android share one jvmAndroidMain source set (java.io is available on both for
// real filesystem paths); androidMain adds the SAF document-tree variant on top. Database-free.
kotlin {
    sourceSets {
        getByName("jvmAndroidMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
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

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
