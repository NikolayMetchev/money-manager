package com.moneymanager.browser

private const val HYPRLAND_ENV = "HYPRLAND_INSTANCE_SIGNATURE"

/**
 * Starts the OS "open a URL" command. AWT's `Desktop.browse` isn't used: it is frequently unsupported on Linux
 * (where it ends in `xdg-open` anyway) and can't raise the browser under Hyprland, which the command's focus
 * step does.
 */
internal actual fun openInSystemBrowser(url: String): Boolean =
    jvmBrowserCommands(System.getProperty("os.name").orEmpty(), System.getenv(HYPRLAND_ENV) != null, url)
        .map(::startWithoutWaiting)
        .last()

/**
 * The commands to start, the opener last. The JVM runs on every desktop OS, so unlike the native targets it
 * picks them at run time; the Hyprland focus step is Linux's.
 */
internal fun jvmBrowserCommands(
    osName: String,
    hyprland: Boolean,
    url: String,
): List<List<String>> {
    val os = osName.lowercase()
    return when {
        "win" in os -> listOf(listOf("rundll32", "url.dll,FileProtocolHandler", url))
        "mac" in os -> listOf(listOf("open", url))
        else -> listOfNotNull(hyprlandFocusCommand(hyprland), linuxBrowserOpenCommand(url))
    }
}

/** Starts [command] and returns at once; false if it couldn't be started. */
internal fun startWithoutWaiting(command: List<String>): Boolean = runCatching { ProcessBuilder(command).start() }.isSuccess
