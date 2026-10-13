# AGENTS.md

Guidance for AI coding agents working on logxy.

## Project

Fast desktop log viewer (Kotlin, Swing, FlatLaf, Gradle).

## Code style

- **Comments only when they add real value.** Explain *why* something is done, non-obvious constraints, or tricky algorithms. Do not comment what the code already says, and do not add comments everywhere.
- **Keep comments short.** One line is usually enough.
- Match the style of the surrounding code.

## Rules

- `core` must not depend on `java.awt` / `javax.swing`.
- Prefer primitive arrays over boxed collections or per-row objects for anything that scales with file size.
- Declare all dependency and plugin versions in `gradle/libs.versions.toml`. Do not add dependencies without a clear reason.
- Speed is the top goal: after a change to parsing, indexing, filtering or merging, run `./gradlew -q :tools:benchmark`. If it fails, find the cause.
- Make code edits with the Edit / Write tools only, never through shell or Python pipelines (`sed`, `python - <<EOF`, ...): each layer eats a level of backslashes, so `\n` escapes in string literals become real line breaks, and `sed -i` rewrites line endings.

## Trying the app

Check UI behaviour in the GUI sandbox (`tools/gui-sandbox/gui.sh`), which keeps the app off the user's desktop: follow `docs/agents/gui-sandbox.md`.

## Commits

- Commit messages are a single short subject line in the imperative, e.g. `Set up project skeleton`. No body listing the changes; the diff shows them.
- Add a body only when there is a non-obvious *why* that the diff cannot show, and keep it to a sentence or two.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `rastislavpapp/logxy`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
