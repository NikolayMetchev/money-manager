import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KotlinMultiplatformExtension> {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            }
        }
    }
}
