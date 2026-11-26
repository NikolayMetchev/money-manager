#!/usr/bin/env bash
# Times a release build's startup (#1254) with `repo-scripts startup-bench`: the desktop app and mm-jvm from
# `//app/main/jvm:app_image_aot` (the app image the installer packages, AOT caches included) and the native
# `//app/main/cli:mm`, each on //tools/bench-db's databases, and appends the results to <out>. perf.yml runs it on
# main with everything built as a release builds it; bazel/perf/record.sh then keeps the results.
#
# The image runs where it is copied to, not installed: the AOT caches were trained against one fixed modification
# time on every file (PINNED_MTIME in JPackageRunner.kt), so the copy is set back to it, as an installer would. The timing
# tool runs on the runner's java (MODULE.bazel's JDK): the image's runtime has no java, as jlink strips the commands.
# On Linux it uses $DISPLAY, else starts Xvfb; on a GitHub macOS runner sudo (bazel/ci/macos_runner_hostname.sh).
#
# Usage: bazel/perf/startup_bench.sh <app image> <mm> <directory with bench.db, empty.db, repo-scripts-tool_deploy.jar> <out.jsonl>
# STARTUP_BENCH_RUNS (default 10) and STARTUP_BENCH_LABEL (the runner image) are passed on to the tool.
set -euo pipefail
# The tool runs each launcher in a home of its own, so every path it is given is absolute.
absolute() { (cd "$(dirname "$1")" && echo "$PWD/$(basename "$1")"); }
image=$1
mm=$(absolute "$2")
bench=$(absolute "$3")
out=$(absolute "$4")
work=$(mktemp -d)

cp -R "$image" "$work/image"
find "$work/image" -exec touch -h -d 2020-01-01T00:00:00Z {} +

case "$(uname -s)" in
  Linux)
    app=$work/image/MoneyManager/bin/MoneyManager
    mm_jvm=$work/image/MoneyManager/bin/mm-jvm
    ;;
  Darwin)
    app=$work/image/MoneyManager.app/Contents/MacOS/MoneyManager
    mm_jvm=$work/image/MoneyManager.app/Contents/MacOS/mm-jvm
    "$(dirname "$0")/../ci/macos_runner_hostname.sh"
    ;;
  MINGW* | MSYS*)
    app=$work/image/MoneyManager/MoneyManager.exe
    mm_jvm=$work/image/MoneyManager/mm-jvm.exe
    ;;
  *)
    echo "unsupported host $(uname -s)" >&2
    exit 2
    ;;
esac
for launcher in "$app" "$mm_jvm" "$mm"; do
  [ -x "$launcher" ] || { echo "no launcher at $launcher" >&2; exit 1; }
done

# Without a display (a CI runner), Xvfb. It is kept running by a supervisor loop: a server dying during minutes of launches would fail the next desktop
# launch ("Can't connect to X11 window server"). -noreset: by default Xvfb resets whenever its last client
# disconnects, which every desktop launch does, and a launch that connects during the reset fails the same way.
if [ "$(uname -s)" = Linux ] && [ -z "${DISPLAY:-}" ]; then
  rm -f /tmp/.X11-unix/X99 /tmp/.X99-lock
  (
    while true; do
      Xvfb :99 -screen 0 1600x1000x24 -noreset >>"$work/xvfb.log" 2>&1 || true
      echo "Xvfb exited; restarting it" >>"$work/xvfb.log"
      rm -f /tmp/.X11-unix/X99 /tmp/.X99-lock
      sleep 0.2
    done
  ) &
  xvfb=$!
  trap 'kill "$xvfb" 2>/dev/null; pkill -x Xvfb 2>/dev/null' EXIT
  for _ in $(seq 50); do
    [ -S /tmp/.X11-unix/X99 ] && break
    sleep 0.2
  done
  export DISPLAY=:99
fi

native() { case "$(uname -s)" in MINGW* | MSYS*) cygpath -w "$1" ;; *) echo "$1" ;; esac; }
if ! java -jar "$(native "$bench/repo-scripts-tool_deploy.jar")" startup-bench \
  --bench-db "$(native "$bench/bench.db")" --empty-db "$(native "$bench/empty.db")" \
  --desktop "$(native "$app")" --mm-jvm "$(native "$mm_jvm")" --mm "$(native "$mm")" \
  --runs "${STARTUP_BENCH_RUNS:-10}" --label "${STARTUP_BENCH_LABEL:-}" \
  --commit-date "$(git log -1 --format=%cI 2>/dev/null || true)" --out "$(native "$out")"; then
  if [ -f "$work/xvfb.log" ]; then
    echo "Xvfb log:" >&2
    cat "$work/xvfb.log" >&2 || true
  fi
  exit 1
fi
