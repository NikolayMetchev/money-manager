package com.moneymanager.browser

import java.awt.Desktop
import java.net.URI

private const val HYPRLAND_ENV = "HYPRLAND_INSTANCE_SIGNATURE"

/**
 * Tries AWT [Desktop] first, then the OS "open a URL" command: `Desktop.browse` is frequently unsupported on
 * Linux, and under Hyprland it can't raise the browser, so there the command's focus step is what's wanted.
 */
internal actual fun openInSystemBrowser(url: String): Boolean =
    openOnJvm(
        url = url,
        osName = System.getProperty("os.name").orEmpty(),
        hyprland = System.getenv(HYPRLAND_ENV) != null,
        browse = ::browseWithDesktop,
        start = ::startWithoutWaiting,
    )

internal fun openOnJvm(
    url: String,
    osName: String,
    hyprland: Boolean,
    browse: (String) -> Boolean,
    start: (List<String>) -> Boolean,
): Boolean = (!hyprland && browse(url)) || start(jvmBrowserOpenCommand(osName, hyprland, url))

private fun browseWithDesktop(url: String): Boolean =
    runCatching {
        Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) &&
            run {
                Desktop.getDesktop().browse(URI(url))
                true
            }
    }.getOrDefault(false)

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
