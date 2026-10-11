package com.moneymanager.files

import kotlinx.io.files.Path
import java.io.File

actual fun userHomeDirectory(): String? = System.getProperty("user.home")

actual fun lastModifiedEpochMs(path: Path): Long = File(path.toString()).lastModified()
