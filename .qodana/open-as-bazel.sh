#!/bin/sh
# The Bazel plugin opens a checkout as a Bazel project (bazel.project.auto.open.if.present, which the Qodana job sets)
# only when it has no .idea; with one, the IDE opens .idea as a plain project and Qodana analyses no modules. So the
# bootstrap moves .idea into .qodana (ignored by git, so a local run's .idea can be moved back).
set -e
if [ -d .idea ]; then
  rm -rf .qodana/idea
  mv .idea .qodana/idea
fi
# PR mode opens the base and head in the same container cache. Bazel enumerates source files
# during sync, so reusing the base revision's project model hides newly added Kotlin files.
# Drop the IDE's per-project state before each import and keep the shared indexes.
rm -rf /data/cache/idea/*/projects
# The Android modules' model needs the SDK platform, which Bazel only downloads once its license is accepted.
sh bazel/ci/accept_android_license.sh
