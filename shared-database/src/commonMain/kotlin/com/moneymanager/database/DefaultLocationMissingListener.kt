package com.moneymanager.database

fun interface DefaultLocationMissingListener {
    fun defaultLocationMissing(): DbLocation
}
