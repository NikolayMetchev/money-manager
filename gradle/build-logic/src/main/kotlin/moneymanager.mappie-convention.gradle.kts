import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import tech.mappie.NamingConvention

plugins {
    alias(conventions.plugins.moneymanager.kotlin.multiplatform.convention)
    alias(libs.plugins.mappie)
}

mappie {
    namingConvention = NamingConvention.LENIENT
}

configure<KotlinMultiplatformExtension> {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.mappie.api)
            }
        }
    }
}
