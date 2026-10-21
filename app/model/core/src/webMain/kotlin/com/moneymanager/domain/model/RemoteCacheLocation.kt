package com.moneymanager.domain.model

/** A database name in the browser's private storage, as Android's is in the app's database directory. */
actual fun remoteCacheLocation(name: String): DbLocation = DbLocation("cloud_${remoteCacheFileName(name)}")
