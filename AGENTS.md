# AGENTS.md

Guidance for AI coding agents working on logxy.

## Project

Fast desktop log viewer (Kotlin, Swing, FlatLaf, Maven).

## Code style

- **Comments only when they add real value.** Explain *why* something is done, non-obvious constraints, or tricky algorithms. Do not comment what the code already says, and do not add comments everywhere.
- **Keep comments short.** One line is usually enough.
- Match the style of the surrounding code.

## Rules

- `core` must not depend on `java.awt` / `javax.swing`.
- Prefer primitive arrays over boxed collections or per-row objects for anything that scales with file size.
- Declare all dependency and plugin versions in the parent POM. Do not add dependencies without a clear reason.

## Commits

- Commit messages are a single short subject line in the imperative, e.g. `Set up project skeleton`. No body listing the changes; the diff shows them.
- Add a body only when there is a non-obvious *why* that the diff cannot show, and keep it to a sentence or two.
