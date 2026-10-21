package greeting

import kotlin.test.Test
import kotlin.test.assertTrue

class GreetingTest {
    @Test
    fun greetsFromThisPlatform() {
        assertTrue(greeting().startsWith("""{"text":"hello","platform":""""), greeting())
    }
}
