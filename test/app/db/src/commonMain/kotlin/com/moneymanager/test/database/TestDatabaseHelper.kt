package com.moneymanager.test.database

import com.moneymanager.domain.model.DbLocation

expect fun createTestDatabaseLocation(): DbLocation

expect fun deleteTestDatabase(location: DbLocation)
