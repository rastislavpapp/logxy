# Changelog

What's new in the logxy IntelliJ plugin, shown as its change notes in the IDE and on JetBrains Marketplace. The app's changes are in its GitHub release notes.

## Unreleased

- A logxy tab in the Debug tool window, beside the Console tab, sharing the run configuration's columns with Run.
- A Debug started beside a Run of the same configuration, or two Runs in parallel, no longer break each other's view.

## 0.1.1 - 2026-10-22

- Runs in IntelliJ-based IDEs from 2025.3, no longer only from 2026.1.
- Startup lines before a run's first log record, such as Gradle's tasks, no longer leave the whole run as raw text.

## 0.1.0 - 2026-10-22

First release.

- A logxy view of each run's output, switched with Console | logxy in the run tab's toolbar.
- Parsed columns, the visual filter editor, the time histogram, templates and live follow.
- Settings › Tools › logxy for parsers, saved filters and log levels, importable from the logxy app.
