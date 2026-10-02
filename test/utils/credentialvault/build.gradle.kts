plugins {
    id("moneymanager.kotlin-multiplatform-convention")
    id("moneymanager.android-convention")
}

// Test support only: an in-memory credential vault for tests that need API/Google secrets without
// touching the file system or prompting for a password.
kotlin {
    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(projects.utils.credentialvault)
                api(projects.utils.localsettings)
            }
        }
        getByName("jvmMain") {
            dependencies {
                api(projects.utils.credentialvault)
                api(projects.utils.localsettings)
            }
        }
    }
}
