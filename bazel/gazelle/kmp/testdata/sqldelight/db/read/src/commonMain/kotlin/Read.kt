package com.example.db.read

import app.cash.sqldelight.db.SqlDriver
import com.example.sql.read.ReadDatabase

fun open(driver: SqlDriver) = ReadDatabase(driver)
