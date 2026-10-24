# AGENTS.md

Guidance for AI coding agents working on logxy.

## Project

Fast desktop log viewer (Kotlin, Swing, FlatLaf, Gradle).

## Code style

- **Comments carry what the code cannot show**: why it is done this way, constraints, contracts, tricky algorithms.
- **Length follows the content**: one line by default, more when the contract needs it (units, threading, nullability, edge cases, the reason for a non-obvious approach) or when one clear line cannot hold it.
- **Readable on the first pass**: a reader new to the class understands a comment or KDoc without rereading it. Write plain sentences that name each thing outright, and say what each parameter or value *is* before what is done with it, with a tiny example when its meaning is not obvious (`2 for "hello" displayed as "he..."`). Older terse, colon-chained KDoc is no precedent.
- **KDoc** fills a gap the name and signature leave: the contract, an edge case, the reason. A declaration whose name already tells all is complete without one, in `core` too: `PlatformDetector.isWindows()`, a `builtIn: Boolean` flag. A KDoc that restates the name is noise, and a review flags it, not its absence. In `core`, the API the app builds on, check each class and non-private function for such a gap.
- `./gradlew build` runs detekt and fails on any finding; `./gradlew detekt --auto-correct` fixes the formatting ones. Readability rules beyond detekt, checked in review: `CODING_STANDARDS.md`.

## Rules

- `core` must not depend on `java.awt` / `javax.swing`.
- `ui` holds the Swing views the app and the IntelliJ plugin share: no FlatLaf and no `kotlinx-coroutines-swing` there (detekt enforces it). The look goes through `Look`, settings and the app-only parts through `LogViewHost`.
- Prefer primitive arrays over boxed collections or per-row objects for anything that scales with file size.
- Declare all dependency and plugin versions in `gradle/libs.versions.toml`. Do not add dependencies without a clear reason.
- Speed is the top goal: after a change to parsing, indexing, filtering or merging, run `./gradlew -q :tools:benchmark`. If it fails, find the cause; for a slow or noisy row, see `docs/agents/benchmarks.md`.
- Make code edits with the Edit / Write tools only, never through shell or Python pipelines (`sed`, `python - <<EOF`, ...): each layer eats a level of backslashes, so `\n` escapes in string literals become real line breaks, and `sed -i` rewrites line endings.

## Trying the app

Check UI behaviour in the GUI sandbox (`tools/gui-sandbox/gui.sh`), which keeps the app off the user's desktop: follow `docs/agents/gui-sandbox.md`. The IntelliJ plugin runs in the same sandbox: follow `docs/agents/ide-sandbox.md`.

## Commits

- The subject is one imperative line, e.g. `Set up project skeleton`, aiming for about 72 characters; go over when the thought needs it.
- A body is optional: a non-obvious *why* the diff cannot show, or, when one commit bundles several separate changes, a short bullet list naming them.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on `rastislavpapp/logxy`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
