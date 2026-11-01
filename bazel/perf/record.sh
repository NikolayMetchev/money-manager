#!/usr/bin/env bash
# Appends startup_bench.py's results to startup.jsonl on the perf-data branch, which webpage/performance.html reads
# (#1254). The branch has no history in common with main and holds only that file; the first run creates it. CI's
# perf-record job runs this on main, in a checkout that can push. Another run may push in between, so a rejected
# push starts again from the new tip.
#
# Usage: bazel/perf/record.sh <directory of *.jsonl results> [branch]
set -euo pipefail
results=$(cd "$1" && pwd)
branch=${2:-perf-data}
message="startup: ${GITHUB_SHA:-$(git rev-parse HEAD)}"

shopt -s nullglob
files=("$results"/*.jsonl)
if [ ${#files[@]} -eq 0 ]; then
  echo "no results in $results" >&2
  exit 1
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
work=$(mktemp -d)
trap 'git worktree remove --force "$work" 2>/dev/null || true; rm -rf "$work"' EXIT

for attempt in 1 2 3 4 5; do
  git worktree remove --force "$work" 2>/dev/null || true
  rm -rf "$work"
  if git fetch -q origin "refs/heads/$branch" 2>/dev/null; then
    git worktree add -q --detach "$work" FETCH_HEAD
  else
    echo "No $branch branch yet; creating it."
    git worktree add -q --detach "$work"
    git -C "$work" switch -q --orphan "new-$branch-$attempt"
  fi
  cat "${files[@]}" >> "$work/startup.jsonl"
  git -C "$work" add startup.jsonl
  git -C "$work" commit -q -m "$message"
  if git -C "$work" push -q origin "HEAD:refs/heads/$branch"; then
    echo "Recorded $(cat "${files[@]}" | wc -l) results on $branch."
    exit 0
  fi
  echo "Push rejected (attempt $attempt); retrying from the branch's new tip." >&2
  sleep $((attempt * 5))
done
echo "couldn't push to $branch" >&2
exit 1
