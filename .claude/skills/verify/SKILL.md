---
name: verify
description: Verify a change by driving the real Compose Desktop app on an isolated nested X display, clicking through it with java.awt.Robot, and capturing screenshots as evidence.
---

# Verify (money-manager, JVM desktop GUI)

The host session is Wayland with no xdotool/ydotool/wtype and no Xvfb — but `Xwayland` is
installed, so run a **rootful nested X server** hosted by the user's compositor. Robot events
sent to the nested display stay inside it (they never touch the user's real pointer).

## Recipe

```bash
# 1. Nested X display (opens one contained window on the user's screen)
Xwayland :9 -geometry 1600x1000 -decorate -retro &

# 2. Launch the app inside it (background; first window paint takes ~15s after gradle is warm)
DISPLAY=:9 ./gradlew :app:main:jvm:run --console=plain

# 3. Screenshot (ImageMagick `import` works against the nested server; no WM needed)
DISPLAY=:9 import -window root shot.png
# Poll for content: identify -format '%k' shot.png  → >10 unique colors means the window painted

# 4. Drive clicks with a java.awt.Robot one-file driver (java 25 runs .java sources directly)
DISPLAY=:9 java Drive.java click X Y     # also: move X Y | scroll N | where
```

`Drive.java`: Robot with `setAutoDelay(40)`, click = `mouseMove` → 150ms → press/release
BUTTON1 with 60ms between. Write it to the scratchpad, not the repo.

Read each screenshot before clicking — compute coordinates from the image (scale is 1:1 on
the nested display). App layout: bottom nav bar (Accounts/Assets/Categories/People/Imports/
Settings); Imports screen has top tabs Directories/CSV/QIF/API/Misc.

## Gotchas

- The app opens the **user's real last-used database** (path shown in the purple header bar).
  Navigation, tabs, and dropdowns are read-only — but do NOT click Download/Import/Delete
  buttons: they hit real provider APIs / mutate real data.
- `xset` is not installed; test the server with `import` or `pgrep -f 'Xwayland :9'` instead.
- Closing the app window (or killing the gradle run task) exits cleanly; kill the Xwayland
  pid afterwards.
- Don't run the GUI while a full `./gradlew build` is running — they contend for the
  configuration cache lock.
