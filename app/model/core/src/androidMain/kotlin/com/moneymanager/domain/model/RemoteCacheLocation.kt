package com.moneymanager.domain.model

actual fun remoteCacheLocation(name: String): DbLocation = DbLocation("cloud_${remoteCacheFileName(name)}")
