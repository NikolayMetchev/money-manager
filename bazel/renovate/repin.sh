#!/usr/bin/env bash
# Re-pins everything a version bump leaves stale (#1029), so a Renovate PR needs no hand edits: the Kotlin/Native
# toolchain when Kotlin moved, Node.js's archive SHA-256s, every Maven lockfile (`bazel run @<hub>//:pin`: maven_kmp's and the tools'
# rules_jvm_external ones), MODULE.bazel.lock, and the
# BUILD files Gazelle writes (a library's packages can move). renovate-pins.yml runs it on Renovate's branches;
# run it by hand after editing MODULE.bazel.
#
# Not covered: aapt2's pins (bazel/kmp/android/pins.bzl, read from AGP's jar, see there), the SQLite
# amalgamation's sha256 in MODULE.bazel, and rules_kotlin's fragments.patch, which a rules_kotlin bump may need
# rebased. Each fails the build loudly.
set -euo pipefail
cd "$(dirname "$0")/../.."

module_kotlin=$(sed -n 's/^KOTLIN = "\([^"]*\)".*/\1/p' MODULE.bazel)
pinned_native=$(sed -n 's/^KOTLIN_NATIVE_VERSION = "\(.*\)"/\1/p' bazel/kmp/native/pins.bzl)
if [ "$module_kotlin" != "$pinned_native" ]; then
  echo "Kotlin/Native is pinned at $pinned_native, MODULE.bazel's Kotlin is $module_kotlin: repinning"
  bazel run @kotlin_native_repin//:repin
fi

python3 bazel/renovate/repin_node.py MODULE.bazel

for hub in $(sed -n 's|.*lock_file = "//bazel/kmp/deps:\(.*\)\.lock\.json".*|\1|p' MODULE.bazel); do
  bazel run "@$hub//:pin"
done
# rules_jvm_external refuses to build from a lockfile its maven.install no longer matches; REPIN=1 lets the pin run.
for hub in $(sed -n 's|.*lock_file = "//bazel/maven:\(.*\)_install\.json".*|\1|p' MODULE.bazel); do
  REPIN=1 bazel run "@$hub//:pin"
done

bazel mod deps --lockfile_mode=update
bazel run //:gazelle
