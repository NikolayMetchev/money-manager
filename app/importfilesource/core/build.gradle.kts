plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// Read-only abstraction for listing + downloading files from an import directory (local folder or
// Google Drive folder). Deliberately separate from app/remotestorage (which is a single-app-folder
// encrypted DB-archive store): this is a plain "list a folder, fetch a file's bytes" surface used by
// the scan/import pipeline, kept database-free so importer modules can depend on it.

kotlin {
    sourceSets {
        // KMP ABI deps are declared per platform source set for dependency-analysis.
        jvmMain {
            dependencies {
                api(projects.app.model.core)
            }
        }

        androidMain {
            dependencies {
                api(projects.app.model.core)
            }
        }
    }
}
