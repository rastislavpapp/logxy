# AGENTS.md

Guidance for AI coding agents working on logxy.

## Project

Fast desktop log viewer (Kotlin, Swing, FlatLaf, Gradle).

## Code style

- **Comments carry what the code cannot show**: why it is done this way, constraints, contracts, tricky algorithms.
- **Length follows the content**: one line by default, more when the contract needs it (units, threading, nullability, edge cases, the reason for a non-obvious approach).
- **KDoc** where the name and signature leave something out: the contract, an edge case, the reason. In `core`, the API the app builds on, look for that on every class and non-private function; where the name and signature already say it all, leave the KDoc out.
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

- The subject is one imperative line, e.g. `Set up project skeleton`, aiming for about 72 characters; go over when the thought needs it.
- A body is optional: a non-obvious *why* the diff cannot show, or, when one commit bundles several separate changes, a short bullet list naming them.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `rastislavpapp/logxy`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
