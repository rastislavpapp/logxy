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

## Trying the app

To check UI behaviour, run the app in the GUI sandbox, never on the user's desktop. `tools/gui-sandbox/gui.sh` (bash, also Git Bash on Windows) runs it on a virtual X display in a podman or docker container (`GUI_ENGINE` overrides the choice), so it cannot steal focus. Each command is one primitive action; drive a scenario interactively by taking a screenshot, reading it, and choosing the next action, rather than writing scripted scenarios.

Test logs are not committed. If the project already has log files that fit the check (often under `test-data/`, but names, sizes and content vary, so look first), use them; otherwise generate one with the tools module (`generate`; `scenario` for a day with traffic, an incident and an outage to find; or `simulate` for a live, growing log). Keep checks fast:

- Use the smallest file the check needs: a few MB for UI behaviour, large files only when the change is about scale. Files are read through a container mount, which is slower than native disk.
- Reuse generated files and keep one sandbox running across checks; restart only after rebuilding the jar.
- Wait by taking another screenshot, not by long sleeps.

```sh
./gradlew -q :app:shadowJar               # the sandbox runs app/build/libs/logxy.jar
./gradlew -q :tools:run --args="generate tools/gui-sandbox/out/small.log 5m"   # optional --seed N
tools/gui-sandbox/gui.sh start tools/gui-sandbox/out/small.log   # any paths; builds the image on first use
tools/gui-sandbox/gui.sh shot filter       # writes tools/gui-sandbox/out/filter.png; read it to see the screen
tools/gui-sandbox/gui.sh click X Y         # screen coordinates read off the latest screenshot
tools/gui-sandbox/gui.sh type "some text"
tools/gui-sandbox/gui.sh key ctrl+o Escape
tools/gui-sandbox/gui.sh logs              # app stdout/stderr
tools/gui-sandbox/gui.sh stop
```

It renders with Linux fonts and look-and-feel, so Windows- or macOS-specific behaviour still needs a check on that OS.

## Commits

- Commit messages are a single short subject line in the imperative, e.g. `Set up project skeleton`. No body listing the changes; the diff shows them.
- Add a body only when there is a non-obvious *why* that the diff cannot show, and keep it to a sentence or two.
