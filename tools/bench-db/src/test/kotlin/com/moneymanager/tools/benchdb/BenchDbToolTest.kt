package com.moneymanager.tools.benchdb

import com.moneymanager.database.JvmDatabaseManager
import com.moneymanager.database.di.DatabaseComponent
import com.moneymanager.database.di.openDatabaseComponent
import com.moneymanager.domain.model.DbLocation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchDbToolTest {
    private val dir = createTempDirectory("bench-db").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun writesThePeopleAccountsAndTransfers() {
        val db = File(dir, "bench.db")
        db.writeText("a stale file the tool replaces")
        main(arrayOf(db.path))

        val (people, accounts, benchTransfers) =
            read(db) { component ->
                val accounts = component.accountRepository.getAllAccounts().first()
                val bench = accounts.single { it.name == BENCH_ACCOUNT }
                Triple(
                    component.personRepository
                        .getAllPeople()
                        .first()
                        .size,
                    accounts.size,
                    component.accountRepository.countTransfersByAccount(bench.id),
                )
            }
        assertEquals(3, people)
        assertEquals(50, accounts)
        // Most transfers touch the benchmark account, so its first page is a real query.
        assertTrue(benchTransfers > 5_000, "$benchTransfers transfers on $BENCH_ACCOUNT")
    }

    @Test
    fun emptyWritesNoAccounts() {
        val db = File(dir, "empty.db")
        main(arrayOf("--empty", db.path))

        assertEquals(
            0,
            read(db) {
                it.accountRepository
                    .getAllAccounts()
                    .first()
                    .size
            },
        )
    }

    private fun <T> read(
        db: File,
        block: suspend (DatabaseComponent) -> T,
    ): T =
        runBlocking {
            val database = JvmDatabaseManager().openDatabase(DbLocation(db.path))
            try {
                block(openDatabaseComponent(database))
            } finally {
                database.close()
            }
        }
}
