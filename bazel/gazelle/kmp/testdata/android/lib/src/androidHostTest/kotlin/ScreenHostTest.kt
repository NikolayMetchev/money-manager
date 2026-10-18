package lib

import android.content.Context
import kotlin.test.Test
import sqlite.Db

class ScreenHostTest {
    @Test
    fun opensOnTheMockableJar() {
        Db(Context())
    }
}
