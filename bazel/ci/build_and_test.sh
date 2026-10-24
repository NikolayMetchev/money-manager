#!/usr/bin/env bash
# CI's Build and Test (build.yml): every Bazel command the job runs, so they can run on a BuildBuddy remote runner
# (`bb remote --script`). The runner resumes from a snapshot of the last run, Bazel server and all, so its analysis
# cache is warm: a fresh GitHub runner spends most of a cached build analysing (~2 minutes on 4 cores) and restoring
# caches. On a fork's PR (no BuildBuddy key) the job runs this script on its own runner instead.
#
# What the job's later steps read goes into $BUILDBUDDY_ARTIFACTS_DIRECTORY (the runner uploads it; build.yml
# downloads it): the logs, coverage.xml, the test reports (testlogs.tar), the Linux installer (installer/) and the
# cross-linked native test binaries (native-tests.tar). The exit code is the build's and tests'.
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
  # jpackage's deb needs fakeroot, sdkmanager a JRE, and the native test binaries zstd.
  command -v fakeroot > /dev/null && command -v java > /dev/null && command -v zstd > /dev/null ||
    { sudo apt-get update -q && sudo apt-get install -yq fakeroot openjdk-21-jre-headless zstd; } || exit 1

  # The SDK platform the Android compiles read (MODULE.bazel's compile_sdk), installed by sdkmanager as on GitHub's
  # runners. local.properties rather than ANDROID_HOME, which rules_android's SDK repository also reads, and fails on
  # without build-tools.
  sdk=$HOME/android-sdk
  echo "sdk.dir=$sdk" > local.properties
  level=$(sed -n 's/^ *compile_sdk = \([0-9]*\),$/\1/p' MODULE.bazel)
  if [ ! -f "$sdk/platforms/android-$level.0/package.xml" ]; then
    sdkmanager=$sdk/cmdline-tools/latest/bin/sdkmanager
    if [ ! -x "$sdkmanager" ]; then
      read -r zip sha1 < <(curl -fsSL https://dl.google.com/android/repository/repository2-3.xml | python3 -c '
import re, sys
package = re.search(r"<remotePackage path=\"cmdline-tools;latest\">(.*?)</remotePackage>", sys.stdin.read(), re.S).group(1)
print(*re.search(r"<checksum[^>]*>([^<]*)</checksum>\s*<url>(commandlinetools-linux-[^<]*)</url>", package).groups()[::-1])
') || exit 1
      tmp=$(mktemp -d)
      curl -fsSL -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$zip" &&
        echo "$sha1  $tmp/tools.zip" | sha1sum -c --quiet &&
        unzip -q "$tmp/tools.zip" -d "$tmp" &&
        mkdir -p "$sdk/cmdline-tools" && mv "$tmp/cmdline-tools" "$sdk/cmdline-tools/latest" || exit 1
      rm -rf "$tmp"
    fi
    yes | "$sdkmanager" --licenses > /dev/null
    "$sdkmanager" --install "platforms;android-$level.0" || exit 1
  fi

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
# The rest are manual, so `//...` skips them:
# - the desktop ProGuard check: `//...` never runs ProGuard, so a new dependency that needs keep/dontwarn rules
#   would otherwise surface only when release.yml ran. It shrinks the desktop app and checks every ServiceLoader
#   provider survived.
# - the Linux desktop installer.
# - the linuxArm64 and mingwX64 test binaries (`:<name>_<target>_cross`) and the launcher that turns their output
#   into JUnit XML, for Native Tests to run on their own runners.
cross=$(bazel query "${flags[@]}" 'attr(name, "_(linuxArm64|mingwX64)_cross$", //...)') || exit 1
bazel test "${flags[@]}" -- //... //app/main/jvm:release_service_providers_test //app/main/jvm:installer \
  //bazel/kmp/testing:native_launcher_deploy.jar $cross 2>&1 | tee "$out/test.log"
status=${PIPESTATUS[0]}

# The JVM and Android host tests' coverage reports, merged into one XML report. It credits code to every test that
# runs it, not only its own module's.
bazel/kmp/testing/coverage.sh "$out/coverage.xml" "${flags[@]}" 2>&1 | tee "$out/coverage.log"

# Codecov reads the test reports (bazel-testlogs is a symlink).
(cd -P bazel-testlogs && find . -name test.xml -print0 | tar --null -cf "$out/testlogs.tar" -T -)

if [ "$status" -eq 0 ]; then
  mkdir -p "$out/installer" && cp bazel-bin/app/main/jvm/installer/* "$out/installer/"
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
exit "$status"
