#!/bin/sh
# The Bazel plugin opens a checkout as a Bazel project (bazel.project.auto.open.if.present, which the Qodana job sets)
# only when it has no .idea; with one, the IDE opens .idea as a plain project and Qodana analyses no modules. So the
# bootstrap moves .idea into .qodana (ignored by git, so a local run's .idea can be moved back).
set -e
if [ -d .idea ]; then
  rm -rf .qodana/idea
  mv .idea .qodana/idea
fi
