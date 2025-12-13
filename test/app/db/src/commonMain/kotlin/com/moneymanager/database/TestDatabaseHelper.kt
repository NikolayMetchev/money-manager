package com.moneymanager.database

expect fun createTestDatabaseLocation(): DbLocation

expect fun deleteTestDatabase(location: DbLocation)
