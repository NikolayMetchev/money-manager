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
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val command =
        when {
            "win" in osName -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            "mac" in osName -> listOf("open", url)
            else -> linuxBrowserOpenCommand(hyprland, url)
        }
    return runCatching { ProcessBuilder(command).start() }.isSuccess
}
