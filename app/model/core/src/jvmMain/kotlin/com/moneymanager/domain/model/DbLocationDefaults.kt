package com.moneymanager.domain.model

import java.nio.file.Paths

val DEFAULT_DATABASE_PATH: DbLocation =
    DbLocation(
        Paths.get(
            System.getProperty("user.home"),
            ".moneymanager",
            DEFAULT_DATABASE_NAME,
        ),
    )
