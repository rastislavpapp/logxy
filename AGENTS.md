# AGENTS.md

Guidance for AI coding agents working on logxy.

## Project

Fast desktop log viewer (Kotlin, Swing, FlatLaf, Gradle).

## Code style

- `./gradlew build` runs detekt and fails on any finding; `./gradlew detekt --auto-correct` fixes the formatting ones.
- Comments, KDoc, domain terms and line shape are checked in review against `CODING_STANDARDS.md`: read it before writing them.

## Rules

- `core` must not depend on `java.awt` / `javax.swing`.
- `ui` holds the Swing views the app and the IntelliJ plugin share: no FlatLaf and no `kotlinx-coroutines-swing` there (detekt enforces it). The look goes through `Look`, settings and the app-only parts through `LogViewHost`.
- Prefer primitive arrays over boxed collections or per-row objects for anything that scales with file size.
- Declare all dependency and plugin versions in `gradle/libs.versions.toml`. Do not add dependencies without a clear reason.
- Speed is the top goal: after a change to parsing, indexing, filtering or merging, run `./gradlew -q :tools:benchmark`. If it fails, find the cause; for a slow or noisy row, or adding one, see `docs/agents/benchmarks.md`.
- Plugin changelog: a new feature or a substantial change in what a plugin user can do gets an entry under Unreleased in `logxy-intellij-plugin/CHANGELOG.md`, in the same branch as the change; that covers `core` and `ui` changes that show in the plugin, not only `logxy-intellij-plugin`. Small tweaks such as a moved button stay out. For a bug fix, ask the user whether it gets an entry.
- Make code edits with the Edit / Write tools only, never through shell or Python pipelines (`sed`, `python - <<EOF`, ...): each layer eats a level of backslashes, so `\n` escapes in string literals become real line breaks, and `sed -i` rewrites line endings.

## Trying the app

Check UI behaviour in the GUI sandbox (`tools/gui-sandbox/gui.sh`), which keeps the app off the user's desktop: follow `docs/agents/gui-sandbox.md`. A change in `ui` or `logxy-intellij-plugin`: also check it in the IDE sandbox, following `docs/agents/ide-sandbox.md`.

## Commits

- Work on a branch `claude/issue-<n>-<slug>` from `main`; changes reach `main` through a PR.
- The subject is one imperative line, e.g. `Set up project skeleton`, aiming for about 72 characters; go over when the thought needs it.
- A body is optional: a non-obvious *why* the diff cannot show, or, when one commit bundles several separate changes, a short bullet list naming them.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `rastislavpapp/logxy`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
