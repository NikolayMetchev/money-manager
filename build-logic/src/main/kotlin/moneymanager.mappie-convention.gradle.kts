import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("tech.mappie.plugin")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<KotlinMultiplatformExtension> {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.findLibrary("mappie-api").get())
            }
        }
    }
}
