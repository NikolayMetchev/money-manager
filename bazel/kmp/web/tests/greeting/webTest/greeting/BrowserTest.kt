package greeting

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserTest {
    @Test
    fun seesTheModulesInternals() {
        assertEquals("$browserTarget (not the JVM)", platform())
    }

    @Test
    fun awaitsAsyncTests() =
        runTest {
            delay(1_000)
            assertEquals("hello", nonJvm("hello").substringBefore(" "))
        }
}
