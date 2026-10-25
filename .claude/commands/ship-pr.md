---
description: Create a draft PR, wait for green CI, mark ready, address all CodeRabbit comments, and get the final PR green
---

Drive the current branch's changes all the way through the PR lifecycle. Work autonomously through
every phase; only stop if you hit something that genuinely needs a human decision (e.g. a CodeRabbit
comment that asks for a product-level choice). PR title/context hint (optional): $ARGUMENTS

## Phase 0 — Branch, commit, push

1. If on `main`, create a feature branch named after the change first.
2. Run `bazel build //... && bazel test //...` locally and make sure it passes (pre-push rule from
   CLAUDE.md). If it fails, fix before continuing.
3. Commit all pending work (end the message with the attribution trailer Claude Code gives for this
   session, which names the model actually in use) and push with `-u`.

## Phase 1 — Draft PR

Create a draft PR against `main` with `gh pr create --draft`, with a body summarising what/why
(end the body with the "🤖 Generated with [Claude Code](https://claude.com/claude-code)" line).

## Phase 2 — Wait for green

Poll `gh pr checks <num> --json name,bucket,link` every ~4 minutes (long waits: use a detached
poll loop and a completion notification rather than blocking; CI takes 10–25 min).

- If a check fails, read its logs (`gh run view <run-id> --log-failed`), fix the cause, run the
  relevant `bazel test` target locally, commit, push, and go back to polling.
- Known flakes in this repo (from project memory):
  - `qodana` reporting masses of "unused" or "unresolved" findings means the IDE didn't open the
    project through Bazel (`.qodana/open-as-bazel.sh`), not that the code changed — check its log
    before fixing anything.
  - `CI Passed` is the one required check: it fails when Build and Test or a Native Tests job fails or is
    cancelled, or when `codecov/patch` or `codecov/project` fails. Fix the job it names, not `CI Passed` itself.
  - Codecov uses its defaults: the patch must reach `main`'s project coverage, and project coverage must
    not drop at all. A codecov failure is never a flake: when one fails,
    read the per-file numbers (`https://api.codecov.io/api/v2/github/NikolayMetchev/repos/money-manager/compare/impacted_files?pullid=<num>`)
    and add tests. A drop in files the PR didn't touch usually means it hid UI that other modules'
    tests used to render. Don't lower the target or add a threshold.
  - `//app/ui/core:test_jvm` ComposeTimeoutException under load is flaky — re-run before investigating.
- Repeat until every required check is green.
- Then fix the failing non-required checks too (Qodana and anything else not in `CI Passed`) where the fix is
  in reach: fix real findings in code, and add a verified false positive to the exclusions its tool keeps
  (Qodana's `qodana.yaml`, under the defect number that explains it) rather than leaving the check red. Leave
  one red only when fixing it is out of the PR's reach (an outage, a check broken on `main` too), and say so
  in the final report.

## Phase 3 — Ready for review

`gh pr ready <num>`.

## Phase 4 — Wait for CodeRabbit

CodeRabbit reviews after the PR leaves draft. Poll every ~3 minutes for:

- review comments: `gh api repos/{owner}/{repo}/pulls/<num>/comments --jq '.[] | select(.user.login == "coderabbitai[bot]")'`
- the review summary: `gh pr view <num> --json reviews,comments`

Wait until the CodeRabbit review has actually arrived (its summary comment plus any inline
comments), not just the "review in progress" placeholder — the walkthrough comment marks
completion. If nothing arrives after ~20 minutes, check the PR timeline for errors and surface
that.

## Phase 5 — Address every comment

For each CodeRabbit inline comment (including ones inside collapsed "Additional comments" /
"Nitpick" sections):

- If the suggestion is correct or harmless-and-reasonable: implement it.
- If it is wrong or inapplicable: don't change code; reply to that comment explaining why
  (`gh api repos/{owner}/{repo}/pulls/<num>/comments/<id>/replies -f body=...`).
- Never silently ignore a comment — every one gets either a code change or a reply.

After implementing fixes: run the relevant tests plus `bazel build //... && bazel test //...`, commit
(same trailer), push. If CodeRabbit re-reviews and raises NEW substantive comments, repeat this
phase (cap at 3 rounds; after that summarise what's still open instead of looping).

## Phase 6 — Final green

Poll checks as in Phase 2 until the final push is fully green, non-required checks included (same flake and fix handling). Then report:
PR URL, checks status, how many CodeRabbit comments were fixed vs replied-to, and anything left
open for the user.
