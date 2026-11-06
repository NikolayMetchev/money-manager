---
description: Create draft PR(s), wait for green CI, mark ready, address all CodeRabbit comments, and get the final PR(s) green. Handles a single branch or a whole `gh stack`, bottom up
---

Drive the current branch's changes all the way through the PR lifecycle. Work autonomously through
every phase; only stop if you hit something that genuinely needs a human decision (e.g. a CodeRabbit
comment that asks for a product-level choice). PR title/context hint (optional): $ARGUMENTS

## Phase 0 — Find the stack, branch, commit, push

**Find what to ship.** Run `gh stack view`. If the current branch is in a stack, the stack is the unit of
work: list its branches **bottom to top** (closest to `main` first). Otherwise the "stack" is the one
current branch, and every phase below applies to that single PR (use plain `git push -u` and `gh pr create`
instead of `gh stack`). Never ship a stack by hand-rolling `--base` PRs: a PR on another PR's branch is
made with `gh stack` (`init`/`add`/`submit`).

**Order rule for the whole command:** every phase runs on the stack **bottom up**. A PR is only worked on
(waited for, marked ready, reviewed) after every PR below it is done with that phase, because a change in a
lower PR restacks and re-runs CI on everything above it.

1. If on `main`, create a feature branch named after the change first.
2. Commit all pending work on the branch it belongs to, not blindly on the current one (end each message
   with the attribution trailer Claude Code gives for this session, which names the model actually in use).
3. Run `bazel build //... && bazel test //...` locally **on the top branch** (it contains every branch
   below it) and make sure it passes (pre-push rule from CLAUDE.md). If it fails, fix on the branch that owns
   the failure, restack (below), and run it again.
4. `gh stack push` and `submit` force-push the **whole** stack, so a branch with commits on origin that you
   don't have would be rolled back. Note each branch's `git rev-parse origin/<b>` before you commit or rebase
   anything (a branch with no origin ref is new: nothing to check). Right before pushing, `git fetch origin`
   and compare: an `origin/<b>` that moved since you noted it holds someone else's commits (Auto-format PR
   pushes too), so stop and reconcile first. Comparing with `git rev-list <b>..origin/<b>` only works before a
   rebase: a restack rewrites a branch's commits, so its old ones always show up as remote-only.
5. Push: `gh stack push` (stack) or `git push -u` (single branch).

**Restacking.** Whenever you commit to a branch that has branches above it: on that branch,
`gh stack rebase --upstack --no-trunk` (cascades the change upward, and leaves `main` out of it). Resolve a
conflict by keeping what both branches meant to do. A clean rebase can still break an upper branch, so re-run
step 3 on the top, then `gh stack push`.

## Phase 1 — Draft PR(s)

Stack: `gh stack submit --auto` creates a draft PR for every branch without one and links them into a stack.
Single branch: `gh pr create --draft` against `main`.

Then give every PR its own title and body with `gh pr edit <num> --title … --body-file …` (submit's titles
are auto-generated): what/why for that PR alone, and for a stacked PR a line saying what it builds on
("Stacked on #<below>") and, for the PRs below, what stacks on them. End each body with the "🤖 Generated
with [Claude Code](https://claude.com/claude-code)" line followed by the session link from the attribution
reminder.

## Phase 2 — Wait for green

For each PR **bottom to top**, poll `gh pr checks <num> --json name,bucket,link` every ~4 minutes (long
waits: use a detached poll loop and a completion notification rather than blocking; CI takes 10–25 min).
Start every PR's checks by pushing the whole stack, but only move on to the next PR once this one is green
(its required checks and, below, the others).

- If a check fails, read its logs (`gh run view <run-id> --log-failed`), fix the cause **on the branch that
  owns the PR**, run the relevant `bazel test` target locally, commit, restack and push (Phase 0), and go back
  to polling from this PR: the push restarted CI above it, and a fix here can break a PR above.
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

Once a PR is green, `gh pr ready <num>`; do the stack bottom up, so the lowest PR is the first to leave draft.

## Phase 4 — Wait for CodeRabbit

Phases 4 and 5 are one loop per PR, bottom to top: get a PR's review, address it, push, and only then trigger
the next PR's, so that each review reads the head its lower PRs' fixes restacked. If a PR's current head can't
get a review, say so in the final report.

CodeRabbit reviews after a PR leaves draft, and this repo is too small for it to review automatically: if no
review starts, comment `@coderabbitai full review` on that PR (plain `review` can fail; it allows one review
per hour, so wait out a rate limit rather than triggering the next PR early). Poll every ~3 minutes for:

- review comments: `gh api repos/{owner}/{repo}/pulls/<num>/comments --jq '.[] | select(.user.login == "coderabbitai[bot]")'`
- the review summary: `gh pr view <num> --json reviews,comments`

Wait until the CodeRabbit review has actually arrived (its summary comment plus any inline
comments), not just the "review in progress" placeholder — the walkthrough comment marks
completion. If nothing arrives after ~20 minutes, check the PR timeline for errors and surface
that.

## Phase 5 — Address every comment

For each CodeRabbit inline comment on the PR (including ones inside collapsed "Additional comments" / "Nitpick"
sections):

- If the suggestion is correct or harmless-and-reasonable: implement it on this PR's branch.
- If it is wrong or inapplicable: don't change code; reply to that comment explaining why
  (`gh api repos/{owner}/{repo}/pulls/<num>/comments/<id>/replies -f body=...`).
- Never silently ignore a comment — every one gets either a code change or a reply.

After implementing a PR's fixes: run the relevant tests, commit (same trailer), restack and push (Phase 0),
then run `bazel build //... && bazel test //...` on the top. A fix in a lower PR changes the PRs above it, so
finish the lower PR's comments before reading the next PR's. If CodeRabbit re-reviews and raises NEW
substantive comments, repeat this phase for that PR (cap at 3 rounds per PR; after that summarise what's still
open instead of looping).

## Phase 6 — Final green

Poll every PR's checks as in Phase 2, bottom to top, until the final push is fully green, non-required
checks included (same flake and fix handling). Do not merge anything: merging stays with the user. Then
report, as one row per PR from the bottom up: PR URL, checks status, how many CodeRabbit comments were
fixed vs replied-to, and anything left open for the user.
