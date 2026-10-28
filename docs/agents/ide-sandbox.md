# Checking the IntelliJ plugin in the GUI sandbox

`logxy-intellij-plugin/ide-sandbox.sh` runs an IntelliJ IDEA or a PyCharm with the plugin in the GUI sandbox's container (see `gui-sandbox.md`), so it never takes focus on the user's desktop. Do not use `runIde`: it opens the IDE on the host. The container takes the GUI sandbox's name, so `tools/gui-sandbox/gui.sh` drives it as it drives the app.

The script reads the IDE from its `product-info.json`: its launcher, its options file, and the demo project to open, `logxy-intellij-plugin/demo/python` in PyCharm and `demo/java` in other IDEs.

## Steps

1. **Build the plugin**: `./gradlew -q :logxy-intellij-plugin:buildPlugin`. Rebuild and start again after every code change.
2. **Pick the IDE.** The script takes an unpacked Linux IDE as its argument; check against the oldest supported version and the newest. IntelliJ IDEA for most checks; PyCharm when a change could behave differently there (see below).
   - Gradle unpacks the version the plugin compiles against, and those `verifyPlugin` checks, into its transforms cache, `~/.gradle/caches/<gradle version>/transforms/<hash>/transformed/idea-<version>` (`pycharm-<version>` for PyCharm); list them with `find ~/.gradle/caches -maxdepth 5 -type d -path '*/transformed/*' \( -name 'idea-*' -o -name 'pycharm-*' \)`. `./gradlew -q :logxy-intellij-plugin:verifyPlugin` fetches IntelliJ IDEA's latest release too, `-PverifyIde=pycharm` PyCharm's oldest and latest, `-PverifyEap` the latest EAP.
   - A Toolbox install works too (`~/.local/share/JetBrains/Toolbox/apps/intellij-idea` on Linux). It is mounted read-only.
   - On Windows and macOS, Gradle unpacks only that OS's build (`idea-<version>-win`), which the Linux container cannot run. Keep a Linux build unpacked in `~/.cache/logxy/ide-linux/` (e.g. `idea-IU-<build>`), outside the repo so a clean leaves it, and look there first.
3. **Start** with `logxy-intellij-plugin/ide-sandbox.sh IDE_HOME`. Each start begins with fresh IDE settings. The IDE opens in free mode, without consent, tips or trust dialogs, after about 20 s: `gui.sh wait demo 90` returns once its window shows, which can be before the project has finished loading, so take a shot before the first click. `gui.sh pause SECS` waits for a run or a dialog to settle.
4. **Run** one of the demo's run configurations. In `demo/java`: "JVM app" (`src/Demo.java`) or "Shell script" (`demo.sh`). Both print log lines, a stack trace or ANSI colours now and then, and echo what is typed into the console. "Busy app" (`src/BusyApp.java`) prints a web shop's Spring Boot 3 log, ten minutes of history at once and then live, with bursts of warnings and errors: for screenshots and checks that need realistic output. "Busy app, custom format" prints the same in a pipe-delimited layout that only parser inference covers. In `demo/python`: "Busy app" (`busy_app.py`) prints the same shop's log through Python's logging to stderr, its errors with tracebacks. In `demo/spring`, which you open with `ide-sandbox.sh IDE_HOME spring`: "Spring app", a Spring Boot application started by the Gradle run configuration `bootRun`. Its run starts with Gradle's task lines and Spring's banner before the first record, so with the logxy tab selected first it shows Raw text, then switches to parsed Spring Boot records with those lines as message-only records at the top. The IDE imports the Gradle project and the first run downloads Gradle and the dependencies, so the container needs network access; before running, wait until the import has finished, which writes `.idea/libraries`: `until tools/gui-sandbox/gui.sh exec test -d /tmp/demo/.idea/libraries; do sleep 5; done`. Without network, pre-fill a Gradle cache on the host and mount it as the container user's `~/.gradle`.
5. **Drive it as a loop** with `gui.sh shot`, `click`, `key` and `type`, as in `gui-sandbox.md`: a full shot to learn the layout, then crops.
6. **Check the log**: `grep -A20 SEVERE tools/gui-sandbox/out/ide-log/idea.log`. The IDE names the plugin it blames. The log appends across starts, so move `ide-log/` aside before a restart.
   - **logxy's lines**: `grep io.github.rastislavpapp.logxy tools/gui-sandbox/out/ide-log/idea.log`. The plugin's own lines (category `#io.github.rastislavpapp.logxy.plugin.…`) start with `logxy:`: where a run's output goes, the view being made and why then, Clear All, the process's end. The session's and the tab's lines, from `core` and `ui`, carry their class name without the `#`: each load with its parser, each reload with why, and failures.
   - **More detail** (each parser detection, why a file reads as Raw text): before the run, Help > Diagnostic Tools > Debug Log Settings (Find Action, `ctrl+shift+a`, "Debug Log Settings"), add the line `io.github.rastislavpapp.logxy`, OK. Its lines show as `FINE`.
7. **Stop** with `gui.sh stop`.

## Reference

- **Regions** at the default 1600x1000 screen: the Run button is about `1098 61`, the run configuration chooser `1025 61`. The Run tool window opens short at the bottom, from about y 640. To make it taller, drag its top edge: `gui.sh exec xdotool mousemove 800 639 mousedown 1 mousemove 800 120 mouseup 1`.
- **Popups**: items in the configuration chooser need the mouse moved onto them before a click registers (`gui.sh move X Y`, then `click`).
- **Keys**: `ctrl+shift+apostrophe` maximizes whichever tool window has focus, not necessarily the Run one.
- **Console links**: a running console scrolls on, so stop the process before clicking a link in it.
- **Inside the container**: the IDE's system directory is `/tmp/ide/system`, so the run spool files are in `/tmp/ide/system/logxy/runs`. The demo project is a copy at `/tmp/demo`. Look with `gui.sh exec ls ...`.
- **Screenshots** for `docs/images/plugin/` are 1200x800 of the IDE alone: start with `GUI_SIZE=1200x819`, maximize the window by double-clicking its title bar, and crop with `gui.sh shot NAME 0 19 1200 800`. Escape in the logxy view clears its filter.
- **IntelliJ's own icons**: for UI that should look like IntelliJ's, start from its icons rather than drawing new ones. They are `expui/<path>.svg` in the `intellij-icons` artifact (`gradle/libs.versions.toml`), loaded through `Look.icon(path, size)`. List them with `unzip -l` on the jar under `~/.gradle/caches/modules-2/files-2.1/com.jetbrains.intellij.platform/icons/`. Not all are 16 px (the editor's `gutter/fold` is 9), and `size` is the size to draw at, so pass the SVG's own size to keep it as IntelliJ shows it.
- **Harmless errors**: JCEF fails to start in the container, so the What's New page stays blank and the log has SEVERE `StartupTest` and `CefApp` entries; their "Last Action" names whatever was clicked last, logxy's actions too. PyCharm also reports that File Watchers needs a plugin it lacks.

## Checking in PyCharm

The plugin verifier only checks the binary against PyCharm's API. A check in PyCharm itself covers what it does differently: a Python run configuration, Python's logging output and tracebacks, the run tab's toolbar. It was done once, for the first Marketplace release; repeat it when the run capture, the run tab's toolbar or the console swap change, or when the oldest supported version moves.

1. `./gradlew -q :logxy-intellij-plugin:verifyPlugin -PverifyIde=pycharm`, which also unpacks PyCharm's oldest supported and latest release.
2. Start the sandbox on each, as above. PyCharm opens `demo/python` with the container's `/usr/bin/python3` as the interpreter, and starts in free mode.
3. Run "Busy app", select the run tab's logxy tab, and check: the parser is "Auto: Python logging", records arrive live, and an ERROR row holds its traceback as extra lines.
4. Check the log for SEVERE entries that blame logxy.

Android Studio is not verified, but the sandbox runs it: its 2026.1 (AI-261) loads the plugin and shows a run in the logxy view.
