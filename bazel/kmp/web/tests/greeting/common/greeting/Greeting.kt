package greeting

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Greeting(
    val text: String,
    val platform: String,
)

expect fun platform(): String

fun greeting(): String = Json.encodeToString(Greeting.serializer(), Greeting("hello", platform()))
