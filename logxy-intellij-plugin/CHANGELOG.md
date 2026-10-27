# Changelog

What's new in the logxy IntelliJ plugin, shown as its change notes in the IDE and on JetBrains Marketplace. The app's changes are in its GitHub release notes.

## Unreleased

- Row highlights: give the rows a query matches a background colour, added and edited from the filter bar's Row highlights button.
- A long one-line record expands the same way as a multi-line record.
- The Run tool window shows logxy as a tab beside the Console tab, as Debug does, instead of the Console | logxy switch in the toolbar. A run of a configuration opens on the tab it was last left on.
- Runs whose console takes typed input, such as a REPL, get the logxy tab too.
- The logxy tab no longer shares a tab with Spring Boot's Beans: it comes after Spring Boot's tabs.
- The logxy tab shows the logxy icon.
- Go to Time (Ctrl+G): a dialog that takes a date and time and jumps to the line closes to them.  
- A date and time picker in the Go to Time dialog and in the Timestamp filter

## 0.1.2 - 2026-10-23

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
