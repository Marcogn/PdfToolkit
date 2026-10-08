---
name: close-phase
description: Closes the current sub-phase or task as CLAUDE.md requires - checks green, independent review of the diff, README/CLAUDE.md/CHANGELOG/decisions updated, commit, push, draft PR, device checks listed. Use when the work of a sub-phase is done, or when the author says "chiudi", "close the phase", "wrap up".
---

# Close a sub-phase

Generic for the author's Android projects (docs/claude.md); the project's own rules are in
CLAUDE.md ("Fixed rule", "Session protocol") and win over this file.

## 1. "Done when" holds

Re-read the sub-phase row and the plan's items. For each one, point to the code or test that
satisfies it. Anything missing: finish it, or ask the author whether it moves to a later phase.

## 2. Checks

Run the `verify` skill. Red means not done.

## 3. Independent review

Two passes, in parallel, on the diff against the base branch:

- `/code-review` (bundled skill): correctness bugs. Fix what is real; for what you leave, say why.
- The `architecture-reviewer` agent: the project's own rules (CLAUDE.md "Rules that aren't
  obvious", REVIEW.md, ADRs). It sees the diff in a fresh context, which is the point: it doesn't
  share the assumptions you made while writing it.

Re-run `verify` if you changed code.

## 4. Documents (CLAUDE.md "Fixed rule")

- `CHANGELOG.md`: every user-visible change under `## [Unreleased]` as `- **Summary.** detail`.
- `CLAUDE.md`: "Current status" (done, date, PR, test count, next sub-phase), "Where things are"
  and "Rules that aren't obvious" if the code changed them, technical limits found.
- An **a** sub-phase: the handoff (at most 15 lines) for its **b**.
- `README.md` if features or commands changed; `docs/decisions.md` for new decisions.
- Keep the docs' language and style; trim, don't pile up.

## 5. Commit, push, PR

- One commit per coherent step, message `<sub-phase>: <what>` like the history (`git log`).
- Push to the session's branch; open a **draft** PR with the repository's template
  (`.github/pull_request_template.md`), then subscribe to its activity.

## 6. Hand back to the author

End with the sub-phase's **device checks** (CLAUDE.md table and plan) as a short checklist the
author can run on the phone, and what is left open. Don't start the next sub-phase.
