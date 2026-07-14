plugins {
    id("moneymanager.android-convention")
    id("moneymanager.kotlin-multiplatform-convention")
}

// The DI scope markers, and nothing else. Every module that contributes a Metro module to a graph needs
// to name its scope; keeping the markers in a leaf module means doing so costs no other dependency.
