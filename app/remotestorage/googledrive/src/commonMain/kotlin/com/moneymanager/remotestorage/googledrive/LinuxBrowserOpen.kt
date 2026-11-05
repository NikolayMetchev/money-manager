package com.moneymanager.remotestorage.googledrive

/**
 * `sh -c` script (the URL is `$1`, passed as an argument so its `&`s are never parsed) that opens a URL with
 * `xdg-open` on Linux.
 *
 * Under Hyprland the browser doesn't come to the front: we aren't a focused Wayland client, so `xdg-open`
 * carries no xdg-activation token and the compositor ignores the request (even with `focus_on_activate`).
 * So there, a background step asks the compositor to focus the default browser's window once the page has
 * had a moment to open. It does nothing elsewhere, or when `hyprctl` is missing.
 */
internal const val LINUX_OPEN_URL_SCRIPT: String = """
if [ -n "${'$'}HYPRLAND_INSTANCE_SIGNATURE" ] && command -v hyprctl >/dev/null 2>&1; then
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
