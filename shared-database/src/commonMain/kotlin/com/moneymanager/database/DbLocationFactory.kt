package com.moneymanager.database

class DbLocationFactory(private val defaultLocation: DbLocation) {
    fun createDbLocation(listener: DefaultLocationMissingListener): DbLocation {
        if (defaultLocation.exists()) {
            return defaultLocation
        }
        return listener.defaultLocationMissing()
    }
}
