import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("moneymanager.kotlin-multiplatform-convention")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KotlinMultiplatformExtension> {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            }
        }
        val jvmMain by getting {
            dependencies {
                runtimeOnly(libs.findLibrary("kotlinx-coroutines-swing").get())
            }
        }
        val androidMain by getting {
            dependencies {
                runtimeOnly(libs.findLibrary("kotlinx-coroutines-android").get())
            }
        }
    }
}
