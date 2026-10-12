import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess

plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.native-convention")
}

// The SQLite every native target links statically (see src/nativeInterop/cinterop/sqlite3.def). JVM and
// Android bring their own SQLite (sqlite-jdbc, the OS), so this module has no code for them.
val sqliteAmalgamation = libs.versions.sqlite.amalgamation
val sqliteRelease = sqliteAmalgamation.release.get()
val downloadSqliteAmalgamation =
    tasks.register<DownloadSqliteAmalgamationTask>("downloadSqliteAmalgamation") {
        description = "Downloads the pinned SQLite amalgamation and verifies its SHA-256."
        url.set("https://www.sqlite.org/${sqliteAmalgamation.year.get()}/sqlite-amalgamation-$sqliteRelease.zip")
        sha256.set(sqliteAmalgamation.sha256)
        outputDirectory.set(layout.buildDirectory.dir("sqlite-amalgamation/$sqliteRelease"))
    }

// The feature set the desktop app's sqlite-jdbc build has and the app relies on (dbstat backs the Settings
// size breakdown). URI filenames are how SQLiter opens in-memory databases; without them it creates a file
// literally named "file:...?mode=memory". Nothing loads extensions, and allowing it would pull in libdl.
val sqliteDefines =
    listOf(
        "SQLITE_THREADSAFE 1",
        "SQLITE_USE_URI 1",
        "SQLITE_ENABLE_DBSTAT_VTAB 1",
        "SQLITE_ENABLE_MATH_FUNCTIONS 1",
        "SQLITE_OMIT_LOAD_EXTENSION 1",
    )

// Apple targets need Xcode's SDK, so their SQLite only builds (and their binaries only link) on macOS. Elsewhere
// their klib still compiles, just without the library embedded.
val hostIsMac = System.getProperty("os.name").startsWith("Mac")

kotlin {
    targets.withType<KotlinNativeTarget>().configureEach {
        val target = this
        compilations.getByName("main").cinterops.create("sqlite3") {
            definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/sqlite3.def"))
        }
        if (target.konanTarget.family.isAppleFamily && !hostIsMac) return@configureEach
        val targetName = name.replaceFirstChar(Char::uppercase)
        val interopTaskName = "cinteropSqlite3$targetName"
        val libraryDirectory = layout.buildDirectory.dir("sqlite/${target.name}")
        val compileSqlite =
            tasks.register<CompileBundledSqliteTask>("compileSqlite$targetName") {
                description = "Compiles the SQLite amalgamation into a static library for ${target.name}."
                amalgamationDirectory.set(downloadSqliteAmalgamation.flatMap { it.outputDirectory })
                konanTarget.set(target.konanTarget.name)
                defines.set(sqliteDefines)
                outputDirectory.set(libraryDirectory)
                // KGP unpacks the Kotlin/Native distribution only when a native task first needs it; the
                // commonizer is one that this module's cinterop (and so this task) doesn't feed.
                dependsOn("commonizeNativeDistribution")
            }
        tasks.named<CInteropProcess>(interopTaskName) {
            inputs.dir(compileSqlite.flatMap { it.outputDirectory }).withPathSensitivity(PathSensitivity.RELATIVE)
            settings.extraOpts(
                "-libraryPath",
                libraryDirectory.get().asFile.absolutePath,
                "-staticLibrary",
                "libsqlite3.a",
            )
        }
    }

    sourceSets {
        nativeTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.sqldelight.native.driver)
                implementation(libs.sqldelight.runtime)
            }
        }
    }
}
