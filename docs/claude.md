# Working with Claude Code (and how to reuse the setup)

How this repository is set up for Claude Code, why each piece exists, and which files to copy into
the author's other Android projects. Like `docs/ci.md`, this page can be copied along with them.
Facts about Claude Code come from its documentation (links at the end), checked on 2026-10-08;
the product changes quickly, so re-check a link before relying on a detail.

## The model: five places Claude gets its behaviour from

| Layer | File(s) | Loaded | Use it for | Why not elsewhere |
|---|---|---|---|---|
| **Memory** | `CLAUDE.md` | Always, every turn | Facts and rules that hold for every task: layout, commands, invariants, status | It costs context on every turn: keep it lean, move procedures out |
| **Skills** | `.claude/skills/<name>/SKILL.md` | Only the description, until used; the body when invoked (`/name`) or when the description matches the request | Procedures: "start a phase", "verify", "close a phase", "fix CI" | Written once, followed the same way every time; no context cost when unused |
| **Subagents** | `.claude/agents/<name>.md` | When delegated to, in a **separate context** | Work that benefits from fresh eyes or that would flood the main context (reviews, wide searches) | A reviewer that didn't write the code doesn't share its assumptions |
| **Hooks** | `.claude/settings.json` → `hooks` | Run by the harness at events (session start, before/after a tool…) | Things that must **always** happen, deterministically | Instructions can be forgotten; a hook can't |
| **Settings** | `.claude/settings.json` → `permissions` | Session start | Commands Claude may run without asking; files it must not read | Fewer prompts locally, explicit limits |

Rule of thumb: *knowledge* → CLAUDE.md, *procedure* → skill, *second opinion* → subagent,
*guarantee* → hook.

Two more files feed reviews: `REVIEW.md` (read by Claude Code Review on GitHub and by the
`architecture-reviewer` agent here) and `.github/pull_request_template.md`.

## What is in this repository

| File | What it does | Generic? |
|---|---|---|
| `.claude/hooks/android-sdk.sh` | Installs the Android SDK (cmdline-tools, licences, platform-tools, the `compileSdk` platform), writes `local.properties`, caps Gradle workers, sets `LC_ALL`. Cloud only; idempotent (≈0.5 s when done) | Yes |
| `.claude/settings.json` | Runs that script at `SessionStart`; allows `./gradlew` and read-only git without prompts; denies reading keystores | Yes |
| `.claude/skills/verify` | Lint + unit tests + debug build, what counts as transient, how to report. Claude Code (v2.1.286+) also runs a project skill named `verify` on its own before each commit that changes code | Yes |
| `.claude/skills/next-phase` | The CLAUDE.md "Session protocol" as steps: model check, reading order, prerequisites, scope | Yes, for projects with a phase table in CLAUDE.md |
| `.claude/skills/close-phase` | "Done when", checks, two independent reviews, docs, commit, draft PR, device checks | Yes |
| `.claude/skills/steward` | How to read this CI's failures and handle review findings. Cloud sessions that watch a PR read it before acting on CI or review events | Yes |
| `.claude/agents/architecture-reviewer.md` | Read-only reviewer of a diff against REVIEW.md, CLAUDE.md rules and ADRs | Yes (rules come from the repo) |
| `REVIEW.md` | What a review must always check here, severity, what to skip | **No**: per project |
| `.github/workflows/claude.yml` | `@claude` in issues and PRs, with the Android toolchain so Claude can build | Yes (`env` block) |
| `.github/workflows/claude-review.yml` | Review posted on a PR when it is opened or marked ready | Yes |
| `.github/dependabot.yml` | Weekly grouped dependency PRs (Gradle) and monthly (Actions) | Yes |
| `.github/pull_request_template.md`, `ISSUE_TEMPLATE/` | Same structure for every PR; issues written so they can be handed to Claude as they are | Yes |

## Reusing it in another Android project

Copy the "generic" files unchanged, then write the two per-project ones: `CLAUDE.md` (run `/init`
for a first draft, then cut it down) and `REVIEW.md` (the project's invariants). Generic files
never name the project, so a fix made in one project can be copied to the others as is.

Why copying and not a plugin: Claude Code can package skills, agents and hooks as a **plugin** in a
marketplace repository, which is the cleanest way to share them, but **cloud sessions don't load
plugins** a repository enables, nor anything in `~/.claude` on your machine. They see only what is
committed in the repository (CLAUDE.md, `.claude/skills`, `.claude/agents`, `.claude/settings.json`)
plus skills enabled on the claude.ai account. Since most sessions here start from the phone, the
committed copy is what works everywhere: cloud, terminal, and the GitHub Action.

Two things *are* shared without copying:

- **The cloud environment.** Use one environment for all Android projects and paste
  `.claude/hooks/android-sdk.sh` into its *Setup script* (claude.ai/code → environment menu → Edit).
  The environment is then cached with the SDK installed (the cache needs the script to finish in
  about five minutes; this one takes ~15 s), and the SessionStart hook in each repository only
  writes `local.properties`.
- **Skills enabled on claude.ai** load in every cloud and terminal session of the account. Good for
  personal, project-independent skills; not versioned in git, and Claude Code's "run `verify` before
  each commit" only works for a project skill, so `verify` stays in the repository.

If the number of projects grows, the generic files can move to a template repository from which
new projects start.

## One-time setup (by the author)

1. **Cloud environment**: setup script as above; network access "Trusted" (Google's Maven and
   `dl.google.com` must be reachable).
2. **Claude GitHub App** on the repository (github.com/apps/claude). Needed for the Action, for
   Code Review and for auto-fix of PRs from cloud sessions.
3. **Secret** `CLAUDE_CODE_OAUTH_TOKEN`: run `claude setup-token` in a terminal (it uses the Claude
   subscription, not API billing), then add it under Settings → Secrets and variables → Actions.
   Until it exists, `claude.yml` and `claude-review.yml` skip themselves.
4. Optional, **Claude Code Review** (managed, multi-agent): Team/Enterprise plans only, billed per
   review (≈15–25 $ each per the docs). On other plans `claude-review.yml` does the job with the
   subscription.

## A typical cycle

1. From the phone: start a cloud session on the repo, `/next-phase`. The model check runs first.
2. For a large sub-phase, ask for a plan first (plan mode), read it, then let it run.
3. Claude works with `verify` as it goes, then `/close-phase`: reviews, docs, draft PR.
4. The session watches the PR: CI failures and review comments wake it (`steward` rules).
5. Mark the PR ready → `claude-review.yml` reviews it. Device checks on the phone, merge.
6. Any time: `@claude` on an issue or PR for small things, without opening a session.

## Habits that pay off

- **Say what "done" is.** Tests to add, device checks, what is out of scope. The issue template
  asks for exactly that.
- **Keep CLAUDE.md short and current.** It is read every turn; long files dilute the rules. Move
  procedures into skills, rationale into `docs/decisions.md` and ADRs.
- **Turn repeated instructions into skills.** If you type the same advice twice, it's a skill.
- **Turn rules Claude broke into checks.** A CI step, a lint rule, a test or a hook beats a
  sentence in CLAUDE.md.
- **Ask for a review in a fresh context** (`/code-review`, the reviewer agent) before merging
  anything non-trivial.
- **Match the model to the work** (the table in CLAUDE.md): Opus for design-heavy steps, Sonnet for
  well-specified ones.

## Coverage

`./gradlew testDebugUnitTest createDebugUnitTestCoverageReport -Pcoverage` writes a JaCoCo report to
`app/build/reports/coverage/test/debug/`; Android CI uploads it as `coverage-report`. It shows where
JVM tests are thin, which is where Claude's changes are least protected. Use it to choose what to
test, not as a percentage to chase.

## What was considered and left out

- **detekt**: the only line that supports Kotlin 2.4 and AGP 9 built-in Kotlin is 2.0.0, still
  alpha, and the project takes stable versions only (`docs/decisions.md`). Revisit at 2.0.0.
- **Hooks that run Gradle after edits or at the end of a turn**: a build takes minutes and would slow
  every turn; `verify` before commits gives the same safety.
- **Scheduled routines** (e.g. a weekly health check): useful only with steady activity; they
  spend the subscription even when nothing changed. Easy to add later from a session.

## Sources

- Claude Code docs: [memory](https://code.claude.com/docs/en/memory),
  [skills](https://code.claude.com/docs/en/skills), [subagents](https://code.claude.com/docs/en/sub-agents),
  [hooks](https://code.claude.com/docs/en/hooks), [settings](https://code.claude.com/docs/en/settings),
  [cloud environments](https://code.claude.com/docs/en/cloud-environments) (what carries over,
  setup scripts, caching), [cloud sessions and auto-fix](https://code.claude.com/docs/en/claude-code-on-the-web),
  [GitHub Actions](https://code.claude.com/docs/en/github-actions),
  [Code Review](https://code.claude.com/docs/en/code-review),
  [plugin marketplaces](https://code.claude.com/docs/en/plugin-marketplaces).
- [claude-code-action security](https://github.com/anthropics/claude-code-action/blob/main/docs/security.md).
- GitHub: [Dependabot supported ecosystems](https://docs.github.com/en/code-security/dependabot/ecosystems-supported-by-dependabot/supported-ecosystems-and-repositories).
- detekt: [2.0.0 changelog](https://detekt.dev/changelog-2.0.0/).
