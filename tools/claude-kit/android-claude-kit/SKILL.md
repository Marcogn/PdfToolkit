---
name: android-claude-kit
description: Installs or updates the author's shared Claude Code kit (skills, reviewer agent, Android SDK hook, Claude and Dependabot workflows, PR and issue templates, opt-in coverage) in the current Android repository, copying it from Marcogn/PdfToolkit and adapting the per-project parts. Use when the author types /android-claude-kit or asks to apply, install, sync or update "the kit" in a repository.
argument-hint: "[optional: PdfToolkit branch or commit to copy from; default main]"
disable-model-invocation: true
---

# Apply the Android Claude kit to this repository

The kit's source of truth is `https://github.com/Marcogn/PdfToolkit` (public); its
`docs/claude.md` explains every file and why it exists. This skill copies the **generic** files
unchanged, adapts the few per-project values, writes the **per-project** ones, verifies the build
and opens a draft PR. Run it again later to bring a repository up to date with the kit.

Work in the repository the session was started on. Be conservative: never change how the app is
built, signed or released beyond what is listed here; anything else becomes a proposal.

## 0. Check the target

- An Android Gradle project: `settings.gradle(.kts)` and an application module (find it from
  `include(...)` and the module with `com.android.application`; usually `app`).
- Read its `CLAUDE.md` (and what it points to), `README.md`, the decisions file and ADRs if any, and
  `.github/workflows/`. Note the docs' language: write per-project text in that language.
- Branch: the one the environment gives, else `claude-kit`.

## 1. Get the kit

Requested kit ref: `$ARGUMENTS` (empty = `main`).

```bash
KIT=$(mktemp -d)/kit
git clone --depth 1 https://github.com/Marcogn/PdfToolkit "$KIT"   # add --branch <ref> if a ref was given
git -C "$KIT" rev-parse --short HEAD   # the kit version, for .claude/kit-version and the PR
```

If `.claude/kit-version` exists, this is an **update**: show the author, per generic file, whether
it changed in the kit since that version (`git -C "$KIT" log --oneline <old>..HEAD -- <file>` needs a
deeper clone: `git -C "$KIT" fetch --unshallow`).

## 2. Generic files: copy unchanged

From `$KIT` to the same path here:

- `.claude/hooks/android-sdk.sh` (keep it executable)
- `.claude/skills/verify/SKILL.md`, `.claude/skills/next-phase/SKILL.md`,
  `.claude/skills/close-phase/SKILL.md`, `.claude/skills/steward/SKILL.md`
- `.claude/agents/architecture-reviewer.md`
- `.github/dependabot.yml`, `.github/pull_request_template.md`, `.github/ISSUE_TEMPLATE/bug.yml`,
  `.github/ISSUE_TEMPLATE/task.yml`
- `.github/workflows/claude.yml`, `.github/workflows/claude-review.yml`
- `docs/claude.md`

Rules:
- If a target file exists and differs from the kit **and** from the previous kit version (a local
  change), don't overwrite silently: show the diff and ask (AskUserQuestion: keep local, take kit,
  merge).
- `.claude/settings.json`: if absent, copy it. If present, merge: add the kit's `SessionStart` hook
  and permission rules, keep every other key of the target.
- `.gitignore`: add `.claude/settings.local.json` if missing.
- `next-phase`/`close-phase` only make sense with a phase plan. If the target has none (no phase
  table in CLAUDE.md or a plan it points to), still copy them, and say in the PR that they will
  stop with a message until a plan exists.

## 3. Per-project values

- `claude.yml`: set `JAVA_VERSION` in its `env` block to the JDK the target's build uses (its
  `android-ci.yml` env, or `compileOptions`/`jvmToolchain`).
- **Coverage**: in the application module's build script, inside `buildTypes`, add (creating the
  `debug { }` block if missing, with the same comment as the kit's `app/build.gradle.kts`):
  `enableUnitTestCoverage = providers.gradleProperty("coverage").isPresent`
- **CI** (`.github/workflows/android-ci.yml`):
  - If it is the shared version (an `env` block with `APP_MODULE`, like the kit's): port the kit's
    coverage changes (the `COVERAGE` env entry, the unit-test step, the upload step), keeping the
    target's other `env` values. If `cleanup-runs.yml` is missing, copy it.
  - If it is an older or different workflow: **don't touch it** (nor `build-apk.yml`,
    `release.yml`): they decide how the app is built, signed and released. Instead propose, as a
    GitHub issue (template "Task"), aligning the workflows with the kit's `docs/ci.md` version,
    with the list of differences you found. Open it once the author agrees.

## 4. Per-project files: write them

- **`REVIEW.md`** at the root, same structure as the kit's (`What Important means here`, `Always
  check`, `Verification bar`, `Do not report`, `Shape`), in the target's docs language, at most ~50
  lines. Build "Always check" from the target's own rules: CLAUDE.md's rules and conventions, ADRs,
  the decisions file. **Verify every rule against the code before writing it** (Grep: where the
  class/package really is, which imports really occur); a rule the code already contradicts is
  either wrong in the docs or a finding: ask, don't write it. Never copy PdfToolkit's rules.
- **`CLAUDE.md`**: minimal edits in its language and style, no rewrite:
  - where things are: one line for `.claude/` (kit skills, reviewer agent, SDK hook; generic, from
    PdfToolkit, see `docs/claude.md`) and one for the Claude/Dependabot workflows;
  - commands: the coverage command;
  - if it has manual Android SDK setup steps for cloud sessions, replace them with a pointer to the
    hook;
  - if the session protocol says out-of-scope findings go into CLAUDE.md notes, align it with the
    kit: bugs and ideas become GitHub issues (see `docs/claude.md`, "Issues").
  - If CLAUDE.md is very long (over ~300 lines), don't trim it here: say in the PR that it is read on
    every turn and propose trimming it as a separate task.
- The decisions file (if any): one dated entry: the kit adopted from PdfToolkit at version `<sha>`,
  what was adapted, what was left out.
- `README.md`: one line pointing to `docs/claude.md` in its documentation list, if it has one.
- `.claude/kit-version`: the kit's short sha and the date, one line.
- No `CHANGELOG.md` entry: nothing user-visible changes.

## 5. Verify

```bash
CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR="$PWD" bash .claude/hooks/android-sdk.sh
LC_ALL=C.UTF-8 ./gradlew lintDebug testDebugUnitTest assembleDebug --console=plain
LC_ALL=C.UTF-8 ./gradlew testDebugUnitTest createDebugUnitTestCoverageReport -Pcoverage --console=plain
```

Run them in the background. Network failures (plugin not found, `Could not resolve`/`Could not
GET`, 429) are normal on a cold Gradle cache: retry up to ~10 times, 15 s apart, stopping at the
first other error. A real failure that the kit didn't cause (red before the change too): report it,
don't fix it here. Note the test count and the coverage line percentage, overall and for the
weakest packages.

Validate what you wrote: JSON/YAML parse, skill and agent frontmatter parse; if `actionlint` can be
downloaded, run it on the workflows you changed.

## 6. Commit, PR, hand back

- One commit `Claude Code kit from PdfToolkit@<sha>` (plus the attribution lines the session asks
  for), push, **draft** PR with the copied PR template filled in: what was copied, what was adapted,
  what was left out and why, verification results, coverage numbers. Subscribe to the PR.
- Reply to the author, in Italian, in a few lines: what the PR contains, the coverage numbers, the
  open proposals (CI alignment issue, CLAUDE.md trimming), and the optional steps from
  `docs/claude.md` ("One-time setup"): the cloud environment setup script is per environment (done
  once for all projects); the `CLAUDE_CODE_OAUTH_TOKEN` secret, only if they want `@claude`, goes in
  each repository.
