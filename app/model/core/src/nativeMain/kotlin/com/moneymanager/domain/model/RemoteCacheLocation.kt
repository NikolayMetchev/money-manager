package com.moneymanager.domain.model

actual fun remoteCacheLocation(name: String): DbLocation = fileRemoteCacheLocation(name)
