#!/usr/bin/env bash
set -euo pipefail
out="$("$1")"
[[ "$out" == "Hello from Kotlin/Native" ]] || { echo "unexpected output: $out" >&2; exit 1; }
