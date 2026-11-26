#!/usr/bin/env bash
# CI's Build and Test (build.yml): every Bazel command the job runs, so they can run on a BuildBuddy remote runner
# (`bb remote --script`). The runner resumes from a snapshot of the last run, Bazel server and all, so its analysis
# cache is warm: a fresh GitHub runner spends most of a cached build analysing (~2 minutes on 4 cores) and restoring
# caches. On a fork's PR (no BuildBuddy key) the job runs this script on its own runner instead.
#
# What the job's later steps read goes into $BUILDBUDDY_ARTIFACTS_DIRECTORY (the runner uploads it; build.yml
# downloads it): the logs, coverage.xml, the test reports (testlogs.tar), and the cross-linked native test binaries
# (native-tests.tar.zst). The exit code is the build's and tests'.
#
# Usage: bazel/ci/build_and_test.sh <bazel flags>
set -uo pipefail
cd "$(dirname "$0")/../.."
out=${BUILDBUDDY_ARTIFACTS_DIRECTORY:?set BUILDBUDDY_ARTIFACTS_DIRECTORY to where the outputs go}
mkdir -p "$out"
flags=("$@")

# On a remote runner: what the GitHub job's setup (bazel-setup, the packaging tools) provides. The snapshot keeps
# both, so only a new runner installs them.
if [ -n "${BUILDBUDDY_CI_RUNNER_ROOT_DIR:-}" ]; then
  # The native test binaries are transferred with zstd.
  command -v zstd > /dev/null ||
    { sudo apt-get update -q && sudo apt-get install -yq zstd; } || exit 1

  bazel/ci/accept_android_license.sh || exit 1

  if [ -n "${BUILDBUDDY_API_KEY:-}" ]; then
    {
      echo "common --config=rbe"
      echo "common --remote_header=x-buildbuddy-api-key=${BUILDBUDDY_API_KEY}"
      echo "common --build_metadata=ROLE=CI"
      [ -z "${REPO_URL:-}" ] || echo "common --build_metadata=REPO_URL=${REPO_URL}"
    } > user.bazelrc
  fi
fi

# `bazel test` builds every target it's given, tests or not, so one invocation builds `//...` and runs its tests.
# Android, Kotlin/Native and the browser are optional (bazel/kmp/optional.bzl): their targets are incompatible, and `//...`
# skips them, unless the platform's flag is on. A pull request without the matching label (build.yml) leaves it off.
NATIVE=${NATIVE:-true}
ANDROID=${ANDROID:-true}
WEB=${WEB:-true}
flags+=("--//bazel/kmp/platforms:native=$NATIVE" "--//bazel/kmp/platforms:android=$ANDROID" "--//bazel/kmp/platforms:web=$WEB")

# Every tool hub's lockfile (bazel/maven/<hub>_install.json) is checked whatever the flags: rules_jvm_external only
# finds a stale one when the hub is fetched, and with a platform off nothing fetches its hubs (android_tools,
# android_lint). Renovate's AGP bump #1585 then passed CI and auto-merged before renovate-pins.yml had re-pinned
# them, and main's Android builds broke. A stale lock fails here, so CI Passed waits for the re-pin.
hubs=()
for lock in bazel/maven/*_install.json; do
  hub=$(basename "$lock" _install.json)
  hubs+=("@$hub//...")
done
bazel fetch "${hubs[@]}"

# The manual cross-linked test binaries and their JUnit launchers run on the native test runners.
extra=()
if [ "$NATIVE" = true ]; then
  cross=$(bazel query "${flags[@]}" 'attr(name, "_(linuxArm64|mingwX64)_cross$", //...)') || exit 1
  # The production job or a PR installer already links mm. A native-only PR still checks the link here.
  if [ "${LINK_MM:-true}" = false ]; then extra+=(-//app/main/cli:mm); fi
  extra+=(//bazel/kmp/testing/launcher:launcher_linuxArm64 //bazel/kmp/testing/launcher:launcher_mingwX64 $cross)
fi
# The build event file names the tests this invocation ran (a skipped one has no testSummary), so the reports below
# leave out what bazel-testlogs keeps from earlier runs: a remote runner resumes a snapshot with its output tree.
bep=$(mktemp)
bazel test "${flags[@]}" --build_event_json_file="$bep" -- //... "${extra[@]}" 2>&1 | tee "$out/test.log"
status=${PIPESTATUS[0]}

# The JVM and Android host tests' coverage reports, merged into one XML report. It credits code to every test that
# runs it, not only its own module's.
ANDROID="$ANDROID" bazel/kmp/testing/coverage.sh "$out/coverage.xml" "${flags[@]}" 2>&1 | tee "$out/coverage.log"

# Codecov reads the test reports (bazel-testlogs is a symlink). A test's report is <package>/<name>/test.xml, under
# external/<repo>/ for another repository's.
grep -o '"testSummary":{"label":"[^"]*"' "$bep" | sed 's/.*"label":"//; s/"$//' | sort -u |
  sed -E 's#^@@?([^/]*)//#external/\1/#; s#^//##; s#:#/#; s#^/##; s#//+#/#g; s#$#/test.xml#' |
  (cd -P bazel-testlogs && while read -r report; do [ ! -f "$report" ] || printf '%s\0' "$report"; done |
    tar --null -cf "$out/testlogs.tar" -T -)
rm -f "$bep"

if [ "$status" -eq 0 ]; then
  if [ "$NATIVE" = true ]; then
    for target in linuxArm64 mingwX64; do
      for binary in $(bazel cquery "${flags[@]}" --output=files "attr(name, \"_${target}_cross\$\", //...)"); do
        dir="native-tests/$target/$(dirname "${binary#bazel-out/*/bin/}")"
        mkdir -p "$dir" && cp "$binary" "$dir/"
      done
      launcher=$(bazel cquery "${flags[@]}" --output=files "//bazel/kmp/testing/launcher:launcher_$target")
      cp "$launcher" "native-tests/$target/test-launcher.${launcher##*.}"
    done
    # 1.6 GB as they are, a quarter of that compressed.
    tar --zstd -cf "$out/native-tests.tar.zst" native-tests && rm -rf native-tests
  fi
fi
exit "$status"
