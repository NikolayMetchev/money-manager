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
    val os =
        when {
            "win" in osName -> DesktopOs.WINDOWS
            "mac" in osName -> DesktopOs.MAC
            else -> DesktopOs.LINUX
        }
    return runCatching { ProcessBuilder(browserOpenCommand(os, hyprland, url)).start() }.isSuccess
}
