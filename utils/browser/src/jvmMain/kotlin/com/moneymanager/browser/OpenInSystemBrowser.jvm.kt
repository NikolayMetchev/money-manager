package com.moneymanager.browser

private const val HYPRLAND_ENV = "HYPRLAND_INSTANCE_SIGNATURE"

/**
 * Starts the OS "open a URL" command. AWT's `Desktop.browse` isn't used: it is frequently unsupported on Linux
 * (where it ends in `xdg-open` anyway) and can't raise the browser under Hyprland, which the command's focus
 * step does.
 */
internal actual fun openInSystemBrowser(url: String): Boolean {
    val osName = System.getProperty("os.name").orEmpty()
    hyprlandFocusCommand(System.getenv(HYPRLAND_ENV) != null)?.takeIf { isLinux(osName) }?.let(::startWithoutWaiting)
    return startWithoutWaiting(jvmBrowserOpenCommand(osName, url))
}

private fun isLinux(osName: String): Boolean = osName.lowercase().let { "win" !in it && "mac" !in it }

/** The JVM runs on every desktop OS, so unlike the native targets it picks the command at run time. */
internal fun jvmBrowserOpenCommand(
    osName: String,
    url: String,
): List<String> {
    val os = osName.lowercase()
    return when {
        "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        "mac" in os -> listOf("open", url)
        else -> linuxBrowserOpenCommand(url)
    }
}

/** Starts [command] and returns at once; false if it couldn't be started. */
internal fun startWithoutWaiting(command: List<String>): Boolean = runCatching { ProcessBuilder(command).start() }.isSuccess
