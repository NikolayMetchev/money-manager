import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(conventions.plugins.moneymanager.kotlin.multiplatform.convention)
    alias(libs.plugins.metro)
}

configure<KotlinMultiplatformExtension> {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.metro.runtime)
            }
        }
    }
}