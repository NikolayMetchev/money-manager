import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

/**
 * Adds a `jvmAndroidMain` (and `jvmAndroidTest`) source set shared by the JVM and Android targets, for
 * code that needs `java.*` APIs both platforms provide (java.math, java.io, java.util.zip, ...).
 *
 * Declared as a group in Kotlin's default hierarchy template rather than by hand-wiring
 * `jvmMain.dependsOn(jvmAndroidMain)`: manual `dependsOn` edges make KGP fall back from the template
 * (KotlinDefaultHierarchyFallbackDependsOnUsageDetected), which modules used to silence by switching the
 * template off in a per-module gradle.properties.
 */
plugins {
    alias(conventions.plugins.moneymanager.android.convention)
}

configure<KotlinMultiplatformExtension> {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndroid") {
                withJvm()
                // The android-kotlin-multiplatform-library target isn't matched by withAndroidTarget(),
                // which only covers the legacy androidTarget().
                withCompilations { it.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }
}
