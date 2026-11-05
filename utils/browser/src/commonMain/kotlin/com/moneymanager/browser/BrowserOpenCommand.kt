package com.moneymanager.browser

/**
 * The argv that opens [url] in the default browser, for the targets that start a process (the JVM and Linux
 * native); the URL is its own argument, so the `&`s in a consent URL are never parsed by a shell.
 *
 * Under Hyprland the browser doesn't come to the front: we aren't a focused Wayland client, so `xdg-open`
 * carries no xdg-activation token and the compositor ignores the request (even with `focus_on_activate`).
 * So there ([hyprland]) a background step asks the compositor to focus the default browser's window once the
 * page has had a moment to open; it does nothing if `hyprctl` is missing.
 */
internal fun browserOpenCommand(
    os: DesktopOs,
    hyprland: Boolean,
    url: String,
): List<String> =
    when {
        os == DesktopOs.WINDOWS -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        os == DesktopOs.MAC -> listOf("open", url)
        hyprland -> listOf("sh", "-c", HYPRLAND_OPEN_URL_SCRIPT, "sh", url)
        else -> listOf("xdg-open", url)
    }

private const val HYPRLAND_OPEN_URL_SCRIPT: String = """
if command -v hyprctl >/dev/null 2>&1; then
  (
    browser=${'$'}(xdg-settings get default-web-browser 2>/dev/null)
    browser=${'$'}{browser%.desktop}
    if [ -n "${'$'}browser" ]; then
      sleep 1
      hyprctl dispatch focuswindow "class:(?i)^${'$'}{browser}${'$'}" >/dev/null 2>&1
    fi
  ) &
fi
exec xdg-open "${'$'}1"
"""
