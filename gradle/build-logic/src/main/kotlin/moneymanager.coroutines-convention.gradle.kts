import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("moneymanager.kotlin-multiplatform-convention")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KotlinMultiplatformExtension> {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            }
        }
        getByName("jvmMain") {
            dependencies {
                runtimeOnly(libs.findLibrary("kotlinx-coroutines-swing").get())
            }
        }
        getByName("androidMain") {
            dependencies {
                runtimeOnly(libs.findLibrary("kotlinx-coroutines-android").get())
            }
        }
    }
}
