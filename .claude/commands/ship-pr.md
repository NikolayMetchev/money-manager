---
description: Create a draft PR, wait for green CI, mark ready, address all CodeRabbit comments, and get the final PR green
---

Drive the current branch's changes all the way through the PR lifecycle. Work autonomously through
every phase; only stop if you hit something that genuinely needs a human decision (e.g. a CodeRabbit
comment that asks for a product-level choice). PR title/context hint (optional): $ARGUMENTS

## Phase 0 — Branch, commit, push

1. If on `main`, create a feature branch named after the change first.
2. Run `./gradlew --console=plain build` locally and make sure it passes (pre-push rule from
   CLAUDE.md). If it fails, fix before continuing.
3. Commit all pending work (end the message with the `Co-Authored-By: Claude Fable 5
   <noreply@anthropic.com>` trailer) and push with `-u`.

## Phase 1 — Draft PR

Create a draft PR against `main` with `gh pr create --draft`, with a body summarising what/why
(end the body with the "🤖 Generated with [Claude Code](https://claude.com/claude-code)" line).

## Phase 2 — Wait for green

Poll `gh pr checks <num> --json name,bucket,link` every ~4 minutes (long waits: use a detached
poll loop and a completion notification rather than blocking; CI takes 10–25 min).

- If a check fails, read its logs (`gh run view <run-id> --log-failed`), fix the cause, run the
  relevant gradle task locally, commit, push, and go back to polling.
- Known flakes in this repo (from project memory):
  - `qodana` sometimes fails with a "languageLevel null" Gradle-import crash and no SARIF — just
    re-run it (`gh run rerun <run-id> --failed`), don't debug it.
  - Jacoco "Stream closed" coverage failures — re-run.
  - `codecov` is NOT a required check; don't block on it.
  - app:ui:core jvmTest ComposeTimeoutException under load is flaky — re-run before investigating.
- Repeat until every required check is green.

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

After implementing fixes: run the relevant tests plus `./gradlew --console=plain build`, commit
(same trailer), push. If CodeRabbit re-reviews and raises NEW substantive comments, repeat this
phase (cap at 3 rounds; after that summarise what's still open instead of looping).

## Phase 6 — Final green

Poll checks as in Phase 2 until the final push is fully green (same flake handling). Then report:
PR URL, checks status, how many CodeRabbit comments were fixed vs replied-to, and anything left
open for the user.
