package greeting

actual fun platform(): String = nonJvm("native")
