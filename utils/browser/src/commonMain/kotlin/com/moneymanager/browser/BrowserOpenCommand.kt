package com.moneymanager.browser

/**
 * The command that opens a URL on Linux. The URL is its own argument, so the `&`s in a consent URL are never
 * parsed by a shell, and it is `xdg-open` itself that gets started, so a machine without it is reported.
 */
internal fun linuxBrowserOpenCommand(url: String): List<String> = listOf("xdg-open", url)

/**
 * Under Hyprland the browser doesn't come to the front: we aren't a focused Wayland client, so `xdg-open`
 * carries no xdg-activation token and the compositor ignores the request (even with `focus_on_activate`).
 * So there ([hyprland]) this separate command, started alongside the opener, asks the compositor to focus the
 * default browser's window once the page has had a moment to open. It does nothing if `hyprctl` is missing,
 * and null elsewhere.
 */
internal fun hyprlandFocusCommand(hyprland: Boolean): List<String>? = if (hyprland) listOf("sh", "-c", HYPRLAND_FOCUS_SCRIPT) else null

// The window class is the browser's desktop entry's StartupWMClass (a Flatpak's file name isn't its class),
// else the entry's name, escaped for the regex hyprctl takes. Multi-dollar string, so the shell's own `$` stays literal.
private const val HYPRLAND_FOCUS_SCRIPT: String = $$"""
command -v hyprctl >/dev/null 2>&1 || exit 0
entry=$(xdg-settings get default-web-browser 2>/dev/null)
[ -n "$entry" ] || exit 0
class=
for dir in "${XDG_DATA_HOME:-$HOME/.local/share}" "$HOME/.local/share/flatpak/exports/share" /var/lib/flatpak/exports/share $(printf %s "${XDG_DATA_DIRS:-/usr/local/share:/usr/share}" | tr : ' '); do
  if [ -r "$dir/applications/$entry" ]; then
    class=$(sed -n 's/^StartupWMClass=//p' "$dir/applications/$entry" | head -n 1)
    break
  fi
done
[ -n "$class" ] || class=${entry%.desktop}
class=$(printf %s "$class" | sed 's/[].[\*^$+?(){}|]/\&/g')
sleep 1
hyprctl dispatch focuswindow "class:(?i)^${class}$" >/dev/null 2>&1
"""
