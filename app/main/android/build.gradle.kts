import org.gradle.work.DisableCachingByDefault

plugins {
    id("moneymanager.android-application-convention")
}

/**
 * Writes the project VERSION file into a generated assets directory, so the app can read it at runtime
 * (VersionReader). Registered through the variant API below, which wires the task in front of every
 * consumer of the assets — no dependsOn on AGP's internal tasks, and nothing written into src/.
 */
@DisableCachingByDefault(because = "Copying one small file is cheaper than a cache round-trip")
abstract class GenerateVersionAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val versionFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        outputDirectory
            .file("VERSION")
            .get()
            .asFile
            .writeText(versionFile.get().asFile.readText())
    }
}

android {
    namespace = "com.moneymanager.android"

    defaultConfig {
        applicationId = "com.moneymanager"
        versionCode = 1
        versionName = "1.0.0"
    }
}

val generateVersionAsset =
    tasks.register<GenerateVersionAsset>("generateVersionAsset") {
        group = "build"
        description = "Copies the project VERSION file into the app's generated assets."
        versionFile.set(layout.settingsDirectory.file("VERSION"))
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateVersionAsset, GenerateVersionAsset::outputDirectory)
    }
}

dependencies {
    implementation(projects.app.cryptodata)
    implementation(projects.app.db.core)
    implementation(projects.app.db.di)
    implementation(projects.app.db.write)
    implementation(projects.app.di.core)
    implementation(projects.app.di.params)
    implementation(projects.app.importengineapi)
    implementation(projects.app.importfilesource.core)
    implementation(projects.app.importfilesource.di)
    implementation(projects.app.model.core)
    implementation(projects.app.remotestorage.core)
    // Native Android Google Drive auth (AndroidGoogleAccessTokenSource) implements the googledrive seam.
    implementation(projects.app.remotestorage.googledrive)
    implementation(projects.app.remotestorage.sync)
    implementation(projects.app.strategycatalog)
    implementation(projects.app.ui.core)
    implementation(projects.app.ui.foundation)
    implementation(projects.utils.credentialvault)
    implementation(projects.utils.localsettings)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.kermit)
    implementation(libs.kermit.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.play.services.auth)
}
