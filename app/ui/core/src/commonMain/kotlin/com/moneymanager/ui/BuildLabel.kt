package com.moneymanager.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Which build of the app is running, shown after the version (the browser's wasmJs or js); null shows nothing. */
val LocalBuildLabel = staticCompositionLocalOf<String?> { null }
