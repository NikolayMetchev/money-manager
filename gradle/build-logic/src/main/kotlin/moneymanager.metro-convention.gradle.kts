import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    alias(conventions.plugins.moneymanager.kotlin.multiplatform.convention)
    alias(libs.plugins.metro)
}

configure<KotlinMultiplatformExtension> {
    // api, not implementation: @ContributesTo / @Provides sit on public interfaces, so the Metro runtime
    // is part of every DI module's ABI. Declared on each platform source set as well as commonMain
    // because dependency-analysis resolves ABI per platform. `matching` rather than `getByName` so the
    // android source set is picked up whenever the Android plugin creates it, however late.
    sourceSets
        .matching { it.name in setOf("commonMain", "jvmMain", "androidMain") }
        .configureEach {
            dependencies {
                api(libs.metro.runtime)
            }
        }
}