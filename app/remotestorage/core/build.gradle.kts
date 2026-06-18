plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// Generic remote-storage abstraction (Google Drive, OneDrive, Dropbox, ...). Pure commonMain and
// deliberately database-free: implementations exchange opaque byte payloads, so the same interface
// serves every backend and the shrink/encrypt pipeline (utils/archive) stays reusable across them.
