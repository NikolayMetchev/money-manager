package com.moneymanager.domain.model

import java.nio.file.Path
import kotlin.io.path.exists

actual data class DbLocation(val path: Path) {
    actual fun exists() = path.exists()

    override fun toString() = path.toString()
}
