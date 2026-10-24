#!/usr/bin/env bash
# Downloads what a BuildBuddy remote run uploaded from $BUILDBUDDY_ARTIFACTS_DIRECTORY (build_and_test.sh's outputs)
# into a directory, at the paths the run gave them, through BuildBuddy's API (GetInvocation, GetFile).
#
# Usage: BUILDBUDDY_API_KEY=… bazel/ci/fetch_artifacts.sh <invocation id> <directory>
set -euo pipefail
invocation=$1
dir=$2
api=https://app.buildbuddy.io/api/v1
call() {
  curl -fsS --retry 3 -X POST "$api/$1" -H "x-buildbuddy-api-key: $BUILDBUDDY_API_KEY" \
    -H 'Content-Type: application/json' -d "$2" "${@:3}"
}

mkdir -p "$dir"
call GetInvocation "{\"selector\": {\"invocation_id\": \"$invocation\"}, \"include_artifacts\": true}" |
  python3 -c '
import json, sys
for artifact in json.load(sys.stdin)["invocation"][0].get("artifacts", []):
    print(artifact["name"], artifact["uri"])
' |
  while read -r name uri; do
    mkdir -p "$(dirname "$dir/$name")"
    call GetFile "{\"uri\": \"$uri\"}" -o "$dir/$name"
    echo "$name"
  done
