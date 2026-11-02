#!/usr/bin/env bash
# Fails when the repository commits Python (#1264): a .py/.pyi file, a python shebang, a python command in a script or
# workflow, or a Bazel Python rule. Scripts are Kotlin (a subcommand of //tools/repo-scripts), Starlark or shell.
# $1 is a file at the repository's root: the test runs unsandboxed (see BUILD.bazel), so its runfiles link to the tree.
set -euo pipefail
root=$(dirname "$(readlink -f "$1")")
cd "$root"
self=bazel/checks/no_python_test.sh

files=$(git ls-files)
bad=0

py=$(grep -E '\.pyi?$' <<<"$files" || true)
if [ -n "$py" ]; then
  echo "Python files are committed:" >&2
  echo "$py" >&2
  bad=1
fi

scripts=$(grep -E '(\.(sh|yml|yaml|bzl)|BUILD\.bazel)$' <<<"$files" | grep -vxF "$self" || true)
# python as a command (first word, after a pipe, &&, ;, $( or command -v), a shebang, or a Python rule.
command_pattern='(^|[|;&(]|\$\()[[:space:]]*(command -v )?python[0-9.]*([[:space:]]|$)|^#!.*python|py_(binary|library|test)\(|rules_python'
if [ -n "$scripts" ]; then
  hits=$(xargs grep -nE "$command_pattern" <<<"$scripts" || true)
  if [ -n "$hits" ]; then
    echo "Python is run or built by:" >&2
    echo "$hits" >&2
    bad=1
  fi
fi

if [ "$bad" -ne 0 ]; then
  echo "No Python in the repo: write the script in Kotlin (tools/repo-scripts), Starlark or shell." >&2
  exit 1
fi
