package com.moneymanager.domain.model

import java.nio.file.Paths

actual fun dbLocationFromString(value: String): DbLocation = DbLocation(Paths.get(value))
