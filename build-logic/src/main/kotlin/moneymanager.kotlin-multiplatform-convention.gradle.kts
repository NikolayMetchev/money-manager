import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KotlinMultiplatformExtension> {
    jvm()

    jvmToolchain(21)

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.findLibrary("kotlinx-coroutines-core").get())
                implementation(libs.findLibrary("kotlinx-datetime").get())
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
