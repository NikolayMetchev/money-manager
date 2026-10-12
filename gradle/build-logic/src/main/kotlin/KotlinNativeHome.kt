import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

/**
 * The Kotlin/Native distribution KGP compiles with, located the way KGP lays it out: an explicit
 * `kotlin.native.home`, else `kotlin-native-prebuilt-<host>-<version>` under the Konan data directory
 * (`konan.data.dir`, `KONAN_DATA_DIR`, or `~/.konan`). KGP's own accessor (`CInteropProcess.konanHome`) is
 * deprecated, and the build treats deprecations as errors.
 */
fun Project.kotlinNativeHome(): Provider<String> {
    val version = getKotlinPluginVersion()
    return providers
        .gradleProperty("kotlin.native.home")
        .orElse(kotlinNativeDataDir().map { "$it/kotlin-native-prebuilt-${kotlinNativeHostName()}-$version" })
}

/** Where Kotlin/Native keeps its distributions and per-target sysroots (`dependencies/`). */
fun Project.kotlinNativeDataDir(): Provider<String> =
    providers
        .gradleProperty("konan.data.dir")
        .orElse(providers.environmentVariable("KONAN_DATA_DIR"))
        .orElse(providers.systemProperty("user.home").map { "$it/.konan" })

private fun kotlinNativeHostName(): String {
    val os = System.getProperty("os.name")
    val osName =
        when {
            os.startsWith("Windows") -> "windows"
            os.startsWith("Mac") -> "macos"
            else -> "linux"
        }
    val arch = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "aarch64" else "x86_64"
    return "$osName-$arch"
}
