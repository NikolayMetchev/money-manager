#!/usr/bin/env bash
# Fails when the repository commits Python (#1264): a .py/.pyi file, a python shebang, a python command in a script or
# workflow, or a Bazel Python rule. Scripts are Kotlin (a subcommand of //tools/repo-scripts), Starlark or shell.
# $1 is a file at the repository's root: the test runs unsandboxed (see BUILD.bazel), so its runfiles link to the tree.
set -euo pipefail
root=$(dirname "$(readlink -f "$1")")
cd "$root"
self=bazel/checks/no_python_test.sh
bad=0

py=$(git ls-files '*.py' '*.pyi')
if [ -n "$py" ]; then
  echo "Python files are committed:" >&2
  echo "$py" >&2
  bad=1
fi

# git grep reads the tracked files (names with spaces included) and skips binaries; it exits 1 for no match, and 2 or
# more for an error, which fails the test (set -e) instead of passing it.
search() {
  local status=0
  git grep -nIE "$1" -- "${@:2}" ":!$self" || status=$?
  if [ "$status" -gt 1 ]; then
    exit "$status"
  fi
  [ "$status" -eq 0 ]
}

# A python shebang in any tracked file, whatever its name.
if search '^#!.*python' .; then
  echo "A script starts with a python shebang (above)." >&2
  bad=1
fi

# python as a command (first word, after a pipe, &&, ;, $(, a YAML key such as `run:`, env or command -v), or a Bazel
# Python rule, in the files that run or build things.
command_pattern='(^|[|;&(:]|\$\()[[:space:]]*(env |command -v )?python[0-9.]*([[:space:]]|$)|py_(binary|library|test)\(|rules_python'
if search "$command_pattern" '*.sh' '*.yml' '*.yaml' '*.bzl' '*.bazel' '*BUILD' '*.bazelrc'; then
  echo "Python is run or built by the lines above." >&2
  bad=1
fi

if [ "$bad" -ne 0 ]; then
  echo "No Python in the repo: write the script in Kotlin (tools/repo-scripts), Starlark or shell." >&2
  exit 1
fi
