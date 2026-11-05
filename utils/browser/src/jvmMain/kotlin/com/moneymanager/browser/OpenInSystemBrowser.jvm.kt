package com.moneymanager.browser

import java.awt.Desktop
import java.net.URI

private const val HYPRLAND_ENV = "HYPRLAND_INSTANCE_SIGNATURE"

/**
 * Tries AWT [Desktop] first, then the OS "open a URL" command: `Desktop.browse` is frequently unsupported on
 * Linux, and under Hyprland it can't raise the browser, so there the command's focus step is what's wanted.
 */
internal actual fun openInSystemBrowser(url: String): Boolean {
    val hyprland = System.getenv(HYPRLAND_ENV) != null
    runCatching {
        if (!hyprland && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            return true
        }
    }
    return startWithoutWaiting(jvmBrowserOpenCommand(System.getProperty("os.name").orEmpty(), hyprland, url))
}

/** The JVM runs on every desktop OS, so unlike the native targets it picks the command at run time. */
internal fun jvmBrowserOpenCommand(
    osName: String,
    hyprland: Boolean,
    url: String,
): List<String> {
    val os = osName.lowercase()
    return when {
        "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        "mac" in os -> listOf("open", url)
        else -> linuxBrowserOpenCommand(hyprland, url)
    }
}

/** Starts [command] and returns at once; false if it couldn't be started. */
internal fun startWithoutWaiting(command: List<String>): Boolean = runCatching { ProcessBuilder(command).start() }.isSuccess
