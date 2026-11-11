#!/usr/bin/env bash
# CI's Build and Test (build.yml): every Bazel command the job runs, so they can run on a BuildBuddy remote runner
# (`bb remote --script`). The runner resumes from a snapshot of the last run, Bazel server and all, so its analysis
# cache is warm: a fresh GitHub runner spends most of a cached build analysing (~2 minutes on 4 cores) and restoring
# caches. On a fork's PR (no BuildBuddy key) the job runs this script on its own runner instead.
#
# What the job's later steps read goes into $BUILDBUDDY_ARTIFACTS_DIRECTORY (the runner uploads it; build.yml
# downloads it): the logs, coverage.xml, the test reports (testlogs.tar), the Linux installer (installer/) and the
# cross-linked native test binaries (native-tests.tar), and the startup benchmark (bench-dbs/: its databases and the tool that times it). The exit code is the build's and tests'.
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
  # jpackage's deb needs fakeroot, and the native test binaries zstd.
  command -v fakeroot > /dev/null && command -v zstd > /dev/null ||
    { sudo apt-get update -q && sudo apt-get install -yq fakeroot zstd; } || exit 1

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
INSTALLER=${INSTALLER:-true}
flags+=("--//bazel/kmp/platforms:native=$NATIVE" "--//bazel/kmp/platforms:android=$ANDROID" "--//bazel/kmp/platforms:web=$WEB")

# The rest are manual, so `//...` skips them:
# - the desktop ProGuard check: `//...` never runs ProGuard, so a new dependency that needs keep/dontwarn rules
#   would otherwise surface only when release.yml ran. It shrinks the desktop app and checks every ServiceLoader
#   provider survived.
# - the Linux desktop installer (a pull request without the `installer` label leaves out it and the tool that times it).
# - the linuxArm64 and mingwX64 test binaries (`:<name>_<target>_cross`) and the launcher that turns their output
#   into JUnit XML, for Native Tests to run on their own runners.
extra=()
if [ "$NATIVE" = true ]; then
  cross=$(bazel query "${flags[@]}" 'attr(name, "_(linuxArm64|mingwX64)_cross$", //...)') || exit 1
  extra+=(//bazel/kmp/testing:native_launcher_deploy.jar $cross)
fi
if [ "$INSTALLER" = true ]; then
  extra+=(//app/main/jvm:installer //tools/repo-scripts:repo-scripts-tool_deploy.jar)
fi
bazel test "${flags[@]}" -- //... //app/main/jvm:release_service_providers_test "${extra[@]}" 2>&1 | tee "$out/test.log"
status=${PIPESTATUS[0]}

# The JVM and Android host tests' coverage reports, merged into one XML report. It credits code to every test that
# runs it, not only its own module's.
ANDROID="$ANDROID" bazel/kmp/testing/coverage.sh "$out/coverage.xml" "${flags[@]}" 2>&1 | tee "$out/coverage.log"

# Codecov reads the test reports (bazel-testlogs is a symlink).
(cd -P bazel-testlogs && find . -name test.xml -print0 | tar --null -cf "$out/testlogs.tar" -T -)

if [ "$status" -eq 0 ]; then
  if [ "$INSTALLER" = true ]; then
    mkdir -p "$out/installer" && cp bazel-bin/app/main/jvm/installer/* "$out/installer/"
    mkdir -p "$out/bench-dbs" && cp bazel-bin/tools/bench-db/bench.db bazel-bin/tools/bench-db/empty.db \
      bazel-bin/tools/repo-scripts/repo-scripts-tool_deploy.jar "$out/bench-dbs/"
  fi
  if [ "$NATIVE" = true ]; then
    for target in linuxArm64 mingwX64; do
      for binary in $(bazel cquery "${flags[@]}" --output=files "attr(name, \"_${target}_cross\$\", //...)"); do
        dir="native-tests/$target/$(dirname "${binary#bazel-out/*/bin/}")"
        mkdir -p "$dir" && cp "$binary" "$dir/"
      done
      cp bazel-bin/bazel/kmp/testing/native_launcher_deploy.jar "native-tests/$target/"
    done
    # 1.6 GB as they are, a quarter of that compressed.
    tar --zstd -cf "$out/native-tests.tar.zst" native-tests && rm -rf native-tests
  fi
fi
exit "$status"
