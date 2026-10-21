package greeting

internal expect val browserTarget: String

actual fun platform(): String = nonJvm(browserTarget)
