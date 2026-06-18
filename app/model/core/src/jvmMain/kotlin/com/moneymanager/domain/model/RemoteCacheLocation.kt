package com.moneymanager.domain.model

import java.nio.file.Paths

actual fun remoteCacheLocation(name: String): DbLocation =
    DbLocation(Paths.get(System.getProperty("user.home"), ".moneymanager", "cloud", remoteCacheFileName(name)))
