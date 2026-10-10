import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(conventions.plugins.moneymanager.kotlin.multiplatform.convention)
}

configure<KotlinMultiplatformExtension> {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        getByName("jvmMain") {
            dependencies {
                runtimeOnly(libs.kotlinx.coroutines.swing)
            }
        }
        getByName("androidMain") {
            dependencies {
                runtimeOnly(libs.kotlinx.coroutines.android)
            }
        }
        // nativeMain needs nothing: coroutines-core already provides native's dispatchers.
    }
}
