package com.moneymanager.test.database.write

import com.moneymanager.database.sql.testwrite.MoneyManagerDatabase
import com.moneymanager.database.write.MoneyManagerDatabaseWrapper

/** The test-only write queries (`test/app/db/write`), over the same driver as the production ones. */
val MoneyManagerDatabaseWrapper.testWrites: MoneyManagerDatabase
    get() = MoneyManagerDatabase(driver)
