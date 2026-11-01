#!/usr/bin/env bash
# Installs the desktop installer `//app/main/jvm:installer` built (deb, dmg or msi, by host) and checks the
# installed app: `mm-jvm version` and the bundled native `mm version` print the version, `mm-jvm pdf-lines` reads a
# PDF through the bundled pdfium, and the desktop app starts and stays up (#1020). The JVM launchers run with
# -XX:AOTMode=on, which fails unless the installed AOT cache loads (#1254). CI runs it on fresh runners; it needs root
# on Linux (apt) and a display (Linux: Xvfb).
#
# Usage: install_smoke_test.sh <installer directory> <expected version>
set -euo pipefail

dir=$1
version=$2
work=$(mktemp -d)

case "$(uname -s)" in
  Linux)
    sudo apt-get install -y "$(realpath "$dir"/*.deb)"
    root=/opt/moneymanager
    app=$root/bin/MoneyManager
    mm_jvm=$root/bin/mm-jvm
    ;;
  Darwin)
    hdiutil attach -nobrowse -mountpoint "$work/dmg" "$dir"/*.dmg
    # ditto keeps modification times, as Finder's copy does: the AOT caches are only valid with them (#1254).
    ditto "$work/dmg/MoneyManager.app" "$work/MoneyManager.app"
    hdiutil detach "$work/dmg"
    root=$work/MoneyManager.app/Contents
    app=$root/MacOS/MoneyManager
    mm_jvm=$root/MacOS/mm-jvm
    ;;
  MINGW* | MSYS*)
    msi=$(cygpath -w "$(realpath "$dir"/*.msi)")
    log=$(cygpath -w "$work/msi.log")
    code=$(powershell -NoProfile -Command \
      "(Start-Process msiexec -ArgumentList '/i','\"$msi\"','/qn','/l*v','\"$log\"' -Wait -PassThru).ExitCode")
    if [ "${code//[$'\r\n']/}" != 0 ]; then
      iconv -f UTF-16LE -t UTF-8 "$work/msi.log" | tail -50 || true
      echo "msiexec exited with $code" >&2
      exit 1
    fi
    # --win-per-user-install
    root=$(cygpath -u "$LOCALAPPDATA")/MoneyManager
    app=$root/MoneyManager.exe
    mm_jvm=$root/mm-jvm.exe
    ;;
  *)
    echo "unsupported host $(uname -s)" >&2
    exit 2
    ;;
esac

echo "Installed into $root:"
# sed, not head: head exits after 40 lines, and under pipefail the SIGPIPE it leaves sort fails the script.
find "$root" -maxdepth 2 | sort | sed -n 1,40p

# --app-content puts cli/ in lib/ on Linux, Contents/ on macOS and the app's root on Windows.
mm=$(find "$root" -path '*/cli/mm' -o -path '*/cli/mm.exe' | head -1)
if [ -z "$mm" ]; then
  echo "no bundled native mm under $root" >&2
  exit 1
fi

# The AOT caches were trained against these modification times (JPackageRunner.PINNED_MTIME).
find "$root" \( -name 'app-main-jvm.jar' -o -name '*.aot' \) -exec ls -l {} +

# AOTMode=on: a JVM launcher whose AOT cache doesn't load fails instead of starting without it.
export JAVA_TOOL_OPTIONS=-XX:AOTMode=on

for cli in "$mm_jvm" "$mm"; do
  out=$("$cli" version </dev/null)
  echo "$cli version: $out"
  grep -q "\"version\": \"$version\"" <<<"$out"
done

# PDF imports: the shrunk jars still carry pdfium, the runtime unpacks and loads it, and lines come out in order.
pdf=$(dirname "$0")/smoke-test.pdf
case "$(uname -s)" in MINGW* | MSYS*) pdf=$(cygpath -w "$pdf") ;; esac
out=$("$mm_jvm" pdf-lines "$pdf" </dev/null)
echo "mm-jvm pdf-lines: $out"
grep -q '"text": "Statement Date 19/01/13"' <<<"$out"
grep -Eq '"text": "Jan 2 +PAYMENT RECEIVED - THANK YOU {2,}[^"]*2,609.36"' <<<"$out"

# A fresh runner, so this is the app's first run: the database setup screen.
if [ "$(uname -s)" = Linux ]; then
  Xvfb :99 -screen 0 1600x1000x24 &
  export DISPLAY=:99
fi
"$app" >"$work/app.log" 2>&1 &
pid=$!
started=
for _ in $(seq 120); do
  if grep -q "DI component created successfully" "$work/app.log"; then
    started=1
    break
  fi
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done
# Still up a while after startup: the first screen composed without crashing the app.
sleep 15
alive=
kill -0 "$pid" 2>/dev/null && alive=1
cat "$work/app.log"
kill "$pid" 2>/dev/null || true
case "$(uname -s)" in MINGW* | MSYS*) taskkill //F //IM MoneyManager.exe >/dev/null 2>&1 || true ;; esac
if [ -z "$started" ] || [ -z "$alive" ]; then
  echo "the desktop app didn't start (started=${started:-no}, alive=${alive:-no})" >&2
  exit 1
fi
echo "The desktop app started and stayed up."
