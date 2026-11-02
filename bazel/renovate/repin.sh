#!/usr/bin/env bash
# Re-pins everything a version bump leaves stale (#1029), so a Renovate PR needs no hand edits: the Kotlin/Native
# toolchain when Kotlin moved, Node.js's and pdfium's archive SHA-256s, the JDK's archives (and .bazelrc's runtime
# version), the bytecode version (the JDK's, as far as Kotlin targets), aapt2's build and checksums when AGP moved,
# Skiko's version (the one Compose was built against), every Maven lockfile (`bazel run @<hub>//:pin`: maven_kmp's and
# the tools' rules_jvm_external ones), MODULE.bazel.lock, and the BUILD files Gazelle writes (a library's packages can
# move). renovate-pins.yml runs it on Renovate's branches; run it by hand after editing MODULE.bazel.
#
# Not covered: the SQLite amalgamation's sha256 in MODULE.bazel, and rules_kotlin's fragments.patch, which a
# rules_kotlin bump may need rebased. Each fails the build loudly.
set -euo pipefail
cd "$(dirname "$0")/../.."

module_kotlin=$(sed -n 's/^KOTLIN = "\([^"]*\)".*/\1/p' MODULE.bazel)
pinned_native=$(sed -n 's/^KOTLIN_NATIVE_VERSION = "\(.*\)"/\1/p' bazel/kmp/native/pins.bzl)
if [ "$module_kotlin" != "$pinned_native" ]; then
  echo "Kotlin/Native is pinned at $pinned_native, MODULE.bazel's Kotlin is $module_kotlin: repinning"
  bazel run @kotlin_native_repin//:repin
fi

# The tool runs on the JDK MODULE.bazel names now, which .bazelrc may not yet (repin jdk is what moves it).
jdk_feature=$(sed -n 's/^JDK = "\([0-9]*\).*/\1/p' MODULE.bazel)
repo_script() {
  bazel run --java_runtime_version="remotejdk_$jdk_feature" --tool_java_runtime_version="remotejdk_$jdk_feature" \
    //tools/repo-scripts:repo-scripts-tool -- "$@"
}

repo_script repin node MODULE.bazel
repo_script repin jdk MODULE.bazel .bazelrc .tool-versions
# rules_kotlin's compiler, whose JvmTarget says how new the bytecode can be.
kotlinc_repo=rules_kotlin++rules_kotlin_extensions+com_github_jetbrains_kotlin_git
bazel fetch --repo="@@$kotlinc_repo"
repo_script repin bytecode "$(bazel info output_base)/external/$kotlinc_repo"
repo_script repin skiko MODULE.bazel
repo_script repin pdfium MODULE.bazel
repo_script repin aapt2 MODULE.bazel bazel/kmp/android/pins.bzl

for hub in $(sed -n 's|.*lock_file = "//bazel/kmp/deps:\(.*\)\.lock\.json".*|\1|p' MODULE.bazel); do
  bazel run "@$hub//:pin"
done
# rules_jvm_external refuses to build from a lockfile its maven.install no longer matches; REPIN=1 lets the pin run.
for hub in $(sed -n 's|.*lock_file = "//bazel/maven:\(.*\)_install\.json".*|\1|p' MODULE.bazel); do
  REPIN=1 bazel run "@$hub//:pin"
done

bazel mod deps --lockfile_mode=update
bazel run //:gazelle
