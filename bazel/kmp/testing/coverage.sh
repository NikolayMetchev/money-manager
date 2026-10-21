#!/usr/bin/env bash
# One coverage report (Kover's XML, which is JaCoCo's format) from the JVM and Android host tests, run first with `--config=coverage`, which leaves each test's
# binary report (coverage.ic, from the IntelliJ coverage engine) in its test.outputs. It counts the production
# modules' classes (each module's _jvm and _android jars, without SQLDelight's generated code), and credits a line to
# every test that ran it, not only its own module's.
#
# Usage: bazel/kmp/testing/coverage.sh <xml-file> [bazel flags, such as --config=rbe]
set -euo pipefail
cd "$(dirname "$0")/../../.."

out=$(realpath -m "$1")
shift
flags=("$@" --config=coverage)

testlogs=$(bazel info "${flags[@]}" bazel-testlogs)
reports=()
for test in $(bazel query "${flags[@]}" 'attr(name, "^test_(jvm|android)$", tests(//...))'); do
  report="$testlogs/$(tr : / <<<"${test#//}")/test.outputs/coverage.ic"
  if [ ! -f "$report" ]; then
    echo "$test has no coverage report: run it with --config=coverage first" >&2
    exit 1
  fi
  reports+=("$report")
done

libs='attr(name, "_(jvm|android)$", kind("kt_jvm_library", //app/... + //utils/... + //tools/...))
  except attr(name, "_sqldelight_", //...)'
bazel build "${flags[@]}" -- $(bazel query "${flags[@]}" "$libs")
# A class both platforms compile reports the JVM jar's bytecode, which is what the JVM tests ran: the reporter keeps
# the first root that has a class, and the Android build's lambdas can be numbered differently.
classfiles=()
for jar in $(bazel cquery "${flags[@]}" "$libs" --output=files | grep '\.jar$' | grep -v -- '-sources\.jar$' |
  sort -t_ -k1,1 | awk '/_jvm\.jar$/ { print; next } { android[++n] = $0 } END { for (i = 1; i <= n; i++) print android[i] }'); do
  classfiles+=(--classfiles "$PWD/$jar")
done

# The XML names files by package, and Codecov maps them onto the repository's paths. Only the HTML report reads
# sources, but the reporter wants a source root.
bazel run "${flags[@]}" //bazel/kmp/testing:kover_cli -- report "${reports[@]}" "${classfiles[@]}" \
  --src "$PWD/app" --xml "$out"
